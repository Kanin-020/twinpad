////! Captura continua de escritorio en Wayland vía portal ScreenCast + PipeWire.
//!
//! Cadena correcta (SPEC del XDG portal):
//! 1. `ScreenCast.CreateSession` / `SelectSources(monitor)` / `Start` —
//!    el owner ve el diálogo de permiso la primera vez por sesión.
//! 2. `OpenPipeWireRemote(session)` → fd de la conexión remota del portal.
//! 3. `pw_context_connect_fd(fd)` — DENTRO de esa conexión, el `node_id`
//!    del stream del portal existe y es un target válido.
//! 4. `Stream` `AUTOCONNECT | MAP_BUFFERS` con `enum_format` amplio: el
//!    compositor elige el formato nativo del panel (en KWin entrega `BGRx`
//!    al tamaño físico del monitor a los fps del panel).
//!
//! Distinto de xcap 0.9.8 en (3): conecta al core por defecto y el
//! `node.target` del portal no existe ahí (medido 2026-10-09:
//! `node.target=98` sin objeto 98 → stream `idle` sin frames). Aquí el
//! enlazo es contra el fd.
//!
//! Salida: se negocias solo formatos de 4 B/px con canal fijo
//! (`BGRx`/`RGBx`/`xRGB`/`RGBA`); el buffer del compositor es fila-mayor,
//! así que se entrega **crudo** al escalador `rgb8_scaled` (lib.rs) junto al
//! [`crate::ChannelLayout`] negociado: el buffer conserva el orden de canal
//! del panel (BGRx en KWin) y leerlo como R,G,B fijo invierte los colores.
//!
//! El pacing lo marca el emisor de video (SPEC-003 §7): esta capa solo
//! publica el frame más reciente y no acumula lag.

use std::{
    collections::HashMap,
    sync::{
        Arc, Condvar, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};
use zbus::{
    MatchRule,
    zvariant::{DeserializeDict, OwnedFd, OwnedObjectPath, Type, Value},
};

/// Espera máxima de cada paso del portal (§9 análogo; el owner puede estar
/// atendiendo el diálogo de permiso la primera vez).
const PORTAL_REPLY_TIMEOUT: Duration = Duration::from_secs(120);

/// Intervalo del tick de parada del worker: el MainLoop se interrumpe con
/// un `quit` ordenado desde el hilo del timer propio (ENG §9: sin I/O
/// síncrona externa).
const WORKER_TICK: Duration = Duration::from_millis(100);

/// Máxima espera del lector por un frame nuevo: el panel pausa si la sesión
/// se congela; el lector no queda colgado eternamente (la capa superior
/// reintenta en su bucle).
const FRAME_WAIT_TIMEOUT: Duration = Duration::from_millis(500);

/// Frame del stream: `(w, h, layout, rgba)` fila-mayor, 4 B/px.
type Frame = (u32, u32, crate::ChannelLayout, Vec<u8>);

/// Buzón de 1 slot productor-consumidor (por qué no `sync_channel`: el
/// `try_send` de cola llena descartaba frames con un log a la tasa del
/// panel y el lector nunca esperaba un frame *nuevo*, solo drenaba viejos
/// — lag). El publicador SOBREESCRIBE (sin errores ni logs) y el lector
/// espera en la condvar hasta que haya algo posterior a su última toma:
/// cero descartes, cero reenvíos de lo ya leído.
#[derive(Default)]
struct FrameMailbox {
    slot: Mutex<Option<Frame>>,
    ready: Condvar,
}

impl FrameMailbox {
    /// Publica sobrescribiendo: nunca bloquea, nunca fracasa, sin log.
    fn publish(&self, frame: Frame) {
        if let Ok(mut slot) = self.slot.lock() {
            *slot = Some(frame);
        }
        self.ready.notify_all();
    }

    /// Espera (con tope) un frame NUEVO y lo consume (deja el slot vacío:
    /// el siguiente `wait_latest` aguarda otra publicación — sin repetir
    /// contenido ya emitido).
    fn wait_latest(&self, timeout: Duration) -> Option<Frame> {
        let guard = self.slot.lock().ok()?;
        let (mut after_wait, _) = self
            .ready
            .wait_timeout_while(guard, timeout, |slot| slot.is_none())
            .ok()?;
        after_wait.take()
    }
}

/// Cuerpo de la `Response` de `SelectSources`: dict `a{sv}` **vacío** — el
/// éxito solo se indica con `code == 0` (spec del portal); un `bool` o una
/// deserialización estricta falla aquí (medido 2026-10-10: el portal responde
/// y el flujo caía a screenshot solo por el tipo).
#[derive(DeserializeDict, Type, Debug, Default)]
#[zvariant(signature = "dict")]
struct NoReply {}

/// Respuesta `CreateSession` del portal.
#[derive(DeserializeDict, Type, Debug)]
#[zvariant(signature = "dict")]
struct SessionReply {
    session_handle: String,
}

/// Cuerpo de la señal `Response` del portal: `(code, payload)`.
#[derive(DeserializeDict, Type, Debug)]
#[zvariant(signature = "dict")]
struct StreamEntry {
    #[allow(dead_code)]
    id: Option<String>,
    #[allow(dead_code)]
    position: Option<(i32, i32)>,
    #[allow(dead_code)]
    size: Option<(i32, i32)>,
    #[allow(dead_code)]
    source_type: Option<u32>,
    #[allow(dead_code)]
    mapping_id: Option<String>,
}

/// Respuesta `Start`: lista de streams concedidos.
#[derive(DeserializeDict, Type, Debug)]
#[zvariant(signature = "dict")]
struct StartReply {
    streams: Option<Vec<(u32, StreamEntry)>>,
}

/// Captura continua activa: retiene la sesión del portal y el hilo de
/// PipeWire. `latest_frame` entrega el frame más reciente del compositor.
pub struct PortalCapture {
    mailbox: Arc<FrameMailbox>,
    running: Arc<AtomicBool>,
    portal_conn: zbus::Connection,
    session_path: OwnedObjectPath,
    worker: Option<thread::JoinHandle<()>>,
}

impl PortalCapture {
    /// Abre la sesión del portal y arranca el stream continuo. Si el owner
    /// cancela el diálogo o el portal no está, `Err` (el caller degrada a
    /// screenshot, R3).
    ///
    /// Todo el flujo del portal corre EN ESTE HILO como una sola tarea async
    /// (async_io::block_on): sin wrapper blocking interleaved, sin executor
    /// compartido — los lock del strace 2026-10-10 no existen aquí.
    ///
    /// # Errors
    /// Portal inalcanzable, cancelación del owner, o sesión sin streams.
    pub fn start() -> Result<Self, ()> {
        let (conn, session_path, fd, node_id) = async_io::block_on(conn_and_setup())?;

        let mailbox = Arc::new(FrameMailbox::default());
        let running = Arc::new(AtomicBool::new(true));
        let worker_running = Arc::clone(&running);
        let reader_mailbox = Arc::clone(&mailbox);
        let handle = thread::Builder::new()
            .name("twinpad-capture".into())
            .spawn(move || {
                pipewire_worker(fd, node_id, mailbox, worker_running);
            })
            .map_err(|_| ())?;

        Ok(Self {
            mailbox: reader_mailbox,
            running,
            portal_conn: conn,
            session_path,
            worker: Some(handle),
        })
    }

    /// Espera y consume el frame MÁS RECIENTE/aún no leído del compositor:
    /// publicaciones intermedias ya se sobrescribieron (R3) y un frame
    /// consumido no se reentrega (sin lag).
    ///
    /// Si el panel pausa (o el worker murió), `None` tras `FRAME_WAIT_TIMEOUT`
    /// — el caller reintenta; jamás bloquea el stream.
    #[must_use]
    pub fn next_frame(&self) -> Option<Frame> {
        self.mailbox.wait_latest(FRAME_WAIT_TIMEOUT)
    }
}

impl Drop for PortalCapture {
    fn drop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        if let Some(handle) = self.worker.take() {
            let _ = handle.join();
        }
        // Cerrar la sesión limpio (§14 análogo: sin sesiones huérfanas). El
        // close es una llamada bloqueante corta en el hilo del drop.
        let conn = zbus::Connection::clone(&self.portal_conn);
        let session = self.session_path.clone();
        let _ = async_io::block_on(close_session(&conn, &session));
    }
} // ---- pasos del portal (ASÍNCRONOS en un solo hilo; async_io::block_on) ----
//
// Por qué async puro: el wrapper blocking de zbus usa async_io::block_on
// interno por llamada y su executor interno compite con el del caller cuando
// señales y method-returns se cruzan (manifestado: caller futex-eterno con
// las respuestas ya en el bus, strace 2026-10-10). Todo el flujo corre aquí
// en UNA tarea async sobre UNA conexión: `select` alterna entre el stream
// `Response` (MatchRule por path) y el method return del call.

/// Setup completo del portal: conexión + 4 fases + canal de frames + spawn
/// del worker PipeWire. Corre como UNA tarea async en el hilo del caller.
type PortalSetup = (zbus::Connection, OwnedObjectPath, OwnedFd, u32);

async fn conn_and_setup() -> Result<PortalSetup, ()> {
    let conn = zbus::Connection::session().await.map_err(|e| {
        println!("capture: sesión DBus no disponible ({e})");
    })?;
    println!("capture: portal fase 1/5 — CreateSession");
    let session_path = create_session(&conn).await.inspect_err(|_| {
        println!("capture: CreateSession fallo");
    })?;
    println!("capture: portal fase 2/5 — SelectSources");
    select_sources(&conn, &session_path)
        .await
        .inspect_err(|_| {
            println!("capture: SelectSources fallo (call o señal Response)");
        })?;
    println!("capture: portal fase 3/5 — Start");
    let (node_id, granted) = start_session(&conn, &session_path).await.inspect_err(|_| {
        println!("capture: Start fallo (owner canceló o señal perdida)");
    })?;
    println!("capture: portal fase 4/5 — nodo {node_id} concedido");
    let _ = (
        granted.id,
        granted.position,
        granted.size,
        granted.source_type,
        granted.mapping_id,
    );
    let fd = open_remote(&conn, &session_path).await?;
    println!("capture: portal fase 5/5 — fd de PipeWire abierto");
    Ok((conn, session_path, fd, node_id))
}

/// Espera `Response` del request path en el bus (regla por path exacto) con
/// timeout (el owner puede tardar en atender el diálogo, U4 SPEC-002 §9).
/// Par protocolo: `(uint code, payload)`; `code != 0` → R3.
///
/// `call_msg` debe llamarse DESPUÉS de crear el stream (AddMatch ya ida).
async fn portal_response<T: for<'de> serde::Deserialize<'de> + Type>(
    conn: &zbus::Connection,
    request_path: &str,
    call: std::pin::Pin<&mut dyn std::future::Future<Output = Result<(), ()>>>,
) -> Result<T, ()> {
    // SIN filtro de sender: el path del request es globalmente único (carry
    // el unique name del sender + token ourmado), filtra por sí solo. El
    // match por well-known name del sender puede fallar si el portal emite
    // la señal con el nombre único (':1.41') en algunos demoneses.
    let rule = MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .interface("org.freedesktop.portal.Request")
        .map_err(|_| ())?
        .member("Response")
        .map_err(|_| ())?
        .path(request_path)
        .map_err(|_| ())?
        .build();
    // Cola amplia: el MatchRule por path exacto solo admitirá la señal
    // esperada, pero el bus puede entregar NameOwnerChanged y demás ruido de
    // la conn al mismo channel — nada se pierde por cap (diagnóstico del
    // strace 2026-10-10: la señal llegó y la cola de 1 se la tragó).
    let mut signal = zbus::MessageStream::for_match_rule(rule, conn, Some(64))
        .await
        .map_err(|_| ())?;

    // La llamada o expiración — el portal puede tardar (diálogo del owner).
    call.await?;
    // La señal llega DESPUÉS del call (el portal la emite al completarse).
    // Timeout con log: el diag de run real distingue señal-perdida de owner-lento.
    let waited = futures_lite::future::or(
        async {
            futures_lite::StreamExt::next(&mut signal)
                .await
                .ok_or_else(|| "EOF".to_string())
        },
        async {
            async_io::Timer::after(PORTAL_REPLY_TIMEOUT).await;
            Err(format!("timeout de {PORTAL_REPLY_TIMEOUT:?} sin Response"))
        },
    )
    .await;
    let msg = match waited {
        Ok(m) => m,
        Err(why) => {
            println!("capture: Response de {request_path} no llegó ({why})");
            return Err(());
        }
    };
    let body = msg.map_err(|_| ())?.body();
    let (code, payload): (u32, T) = body.deserialize().map_err(|e| {
        println!("capture: Response payload de {request_path} indeserializable ({e})");
    })?;
    if code != 0 {
        println!("capture: portal respondió code={code} (owner canceló o error)");
        return Err(());
    }
    Ok(payload)
}

async fn create_session(conn: &zbus::Connection) -> Result<OwnedObjectPath, ()> {
    let portal = screencast_proxy_async(conn).await?;
    let token = portal_token();
    let request = request_path_of_async(&portal, &token).ok_or(())?;
    let mut options: HashMap<&str, Value<'_>> = HashMap::new();
    options.insert("handle_token", Value::from(token.as_str()));
    options.insert("session_handle_token", Value::from(token.as_str()));
    let mut call = Box::pin(async {
        portal
            .call_method("CreateSession", &(options))
            .await
            .map(|_| ())
            .map_err(|_| ())
    });
    let reply = portal_response::<SessionReply>(conn, &request, call.as_mut()).await?;
    reply.session_handle.try_into().map_err(|_| ())
}

async fn select_sources(conn: &zbus::Connection, session: &OwnedObjectPath) -> Result<(), ()> {
    let portal = screencast_proxy_async(conn).await?;
    let token = portal_token();
    let request = request_path_of_async(&portal, &token).ok_or(())?;
    let mut options: HashMap<&str, Value<'_>> = HashMap::new();
    options.insert("handle_token", Value::from(token.as_str()));
    options.insert("types", Value::from(1_u32)); // MONITOR
    options.insert("multiple", Value::from(false));
    options.insert("cursor_mode", Value::from(1_u32)); // embedded
    let mut call = Box::pin(async {
        portal
            .call_method("SelectSources", &(session, options))
            .await
            .map(|_| ())
            .map_err(|e| {
                println!("capture: SelectSources call errora: {e}");
            })
    });
    portal_response::<NoReply>(conn, &request, call.as_mut())
        .await
        .map(|_| ())
}

async fn start_session(
    conn: &zbus::Connection,
    session: &OwnedObjectPath,
) -> Result<(u32, StreamEntry), ()> {
    let portal = screencast_proxy_async(conn).await?;
    let token = portal_token();
    let request = request_path_of_async(&portal, &token).ok_or(())?;
    let mut options: HashMap<&str, Value<'_>> = HashMap::new();
    options.insert("handle_token", Value::from(token.as_str()));
    let mut call = Box::pin(async {
        portal
            .call_method("Start", &(session, "", options))
            .await
            .map(|_| ())
            .map_err(|_| ())
    });
    let reply = portal_response::<StartReply>(conn, &request, call.as_mut()).await?;
    let streams = reply.streams.ok_or(())?;
    streams.into_iter().next().ok_or(())
}

async fn open_remote(conn: &zbus::Connection, session: &OwnedObjectPath) -> Result<OwnedFd, ()> {
    let portal = screencast_proxy_async(conn).await?;
    let options: HashMap<&str, Value<'_>> = HashMap::new();
    let reply = portal.call("OpenPipeWireRemote", &(session, options)).await;
    reply.map_err(|_| ())
}

async fn close_session(conn: &zbus::Connection, session: &OwnedObjectPath) -> Result<(), ()> {
    let portal = screencast_proxy_async(conn).await?;
    let options: HashMap<&str, Value<'_>> = HashMap::new();
    portal
        .call_method("Close", &(session, options))
        .await
        .map(|_| ())
        .map_err(|_| ())
}

async fn screencast_proxy_async(conn: &zbus::Connection) -> Result<zbus::Proxy<'_>, ()> {
    zbus::Proxy::new(
        conn,
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.ScreenCast",
    )
    .await
    .map_err(|_| ())
}

/// Token exclusivo por llamada: aísla la señal `Response` de otros requests.
fn portal_token() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    format!("twinpad_{nanos}")
}

/// Path del request derivado de la convención del portal:
/// `/org/freedesktop/portal/desktop/request/<SENDER>/<TOKEN>`.
/// `None` solo si el token no es ASCII (imposible con `portal_token`);
/// el caller degrada a screenshot (R3).
fn request_path_of_async(portal: &zbus::Proxy<'_>, token: &str) -> Option<OwnedObjectPath> {
    let sender = portal
        .connection()
        .unique_name()
        .map(|n| n.trim_start_matches(':').replace('.', "_"))
        .unwrap_or_else(|| "twinpad".into());
    format!("/org/freedesktop/portal/desktop/request/{sender}/{token}")
        .try_into()
        .map_err(|_| ())
        .ok()
}

// ---- worker PipeWire (hilo propio; el caller solo lee frames) ----

/// Flags del stream: `AUTOCONNECT` enlaza contra el `node_id` del portal;
/// `MAP_BUFFERS` expone el buffer del compositor como memoria mapeada en
/// este proceso (sin copia extra). Sin `RT_PROCESS`: el callback corre en
/// el thread del loop y aquí no hay presupuesto realtime que honrar.
fn stream_flags() -> pipewire::stream::StreamFlags {
    pipewire::stream::StreamFlags::AUTOCONNECT | pipewire::stream::StreamFlags::MAP_BUFFERS
}

/// Bucle de captura del worker (hilo dedicado): MainLoop de PipeWire en el
/// propio hilo, contexto conectado al fd del portal, stream de entrada con
/// negociación amplia. Publica cada buffer a la cola del caller.
use pipewire::properties::properties as pw_properties;

fn pipewire_worker(
    fd: OwnedFd,
    node_id: u32,
    mailbox: Arc<FrameMailbox>,
    running: Arc<AtomicBool>,
) {
    pipewire::init();

    let Ok(tloop) = pipewire::main_loop::MainLoopRc::new(None) else {
        println!("capture: main loop PipeWire no creable");
        return;
    };
    let Ok(context) = pipewire::context::ContextRc::new(&tloop, None) else {
        println!("capture: contexto PipeWire no creable");
        return;
    };
    // Clave del enlazo (por qué el stub viejo daba 0 frames): `connect_fd`
    // contra el fd del portal — DENTRO de ese core el nodo del portal
    // existe; el core por defecto jamás lo vería.
    let Ok(core) = context.connect_fd_rc(fd.into(), None) else {
        println!("capture: connect_fd contra el portal falló");
        return;
    };

    let mut props = pw_properties! {
        *pipewire::keys::MEDIA_TYPE => "Video",
        *pipewire::keys::MEDIA_CATEGORY => "Capture",
        *pipewire::keys::MEDIA_ROLE => "Screen",
    };
    props.insert(*pipewire::keys::NODE_NAME, "twinpad-capture");

    let Ok(stream) = pipewire::stream::StreamBox::new(&core, "twinpad-capture", props) else {
        println!("capture: stream PipeWire no creable");
        return;
    };

    // `(w, h, layout)` negociado por `param_changed`, leído por el `process`:
    // cruzan el thread del loop a este hilo vía Arc<Mutex>.
    let negotiated: Arc<Mutex<Option<(u32, u32, crate::ChannelLayout)>>> = Arc::default();
    let size_in_process = Arc::clone(&negotiated);
    let publish_mailbox = Arc::clone(&mailbox);
    let running_timer = Arc::clone(&running);

    // El listener vive mientras no se suelte: se retiene hasta el final del
    // hilo (drop de `listener` después de `quit`).
    let listener = stream
        .add_local_listener_with_user_data(size_in_process)
        .state_changed(|_, _, old, new| {
            // Log diagnóstico ENG §7: estados de flujo, sin contenido.
            println!("capture: stream estado {old:?} → {new:?}");
        })
        .param_changed(|_, size_slot, id, param| {
            use pipewire::spa::param::ParamType;
            use pipewire::spa::param::format::MediaSubtype;
            use pipewire::spa::param::format::MediaType;
            use pipewire::spa::param::format_utils;

            let Some(param) = param else {
                return;
            };
            if id != ParamType::Format.as_raw() {
                return;
            }
            let Ok((media_type, media_subtype)) = format_utils::parse_format(param) else {
                return;
            };
            if media_type != MediaType::Video || media_subtype != MediaSubtype::Raw {
                return;
            }
            let mut info = pipewire::spa::param::video::VideoInfoRaw::new();
            if info.parse(param).is_err() {
                return;
            }
            let size = info.size();
            let w = size.width;
            let h = size.height;
            // Layout de canal REAL del panel (por qué: BGRx es lo que KWin
            // entrega y el escalador sin el índice correcto invierte R/B).
            let layout = match info.format() {
                pipewire::spa::param::video::VideoFormat::BGRx => crate::ChannelLayout::Bgrx,
                pipewire::spa::param::video::VideoFormat::RGBx => crate::ChannelLayout::Rgbx,
                pipewire::spa::param::video::VideoFormat::xRGB => crate::ChannelLayout::Xrgb,
                pipewire::spa::param::video::VideoFormat::RGBA => crate::ChannelLayout::Rgba,
                other => {
                    println!("capture: formato no soportado {other:?}");
                    return;
                }
            };
            println!(
                "capture: formato negociado {other_canonical:?} {w}×{h}",
                other_canonical = info.format()
            );
            // Lock envenenado solo si otro hilo paniqueara sosteniéndolo:
            // se degrada a no-negociado (la sesión corta por sí sola).
            if let Ok(mut slot) = size_slot.lock() {
                *slot = Some((w, h, layout));
            }
        })
        .process(move |stream, size_slot| {
            let Some(mut buffer) = stream.dequeue_buffer() else {
                return;
            };
            let datas = buffer.datas_mut();
            if datas.is_empty() {
                return;
            }
            let Some(bytes) = datas[0].data() else {
                return;
            };
            let Some((w, h, layout)) = size_slot.lock().ok().and_then(|slot| *slot) else {
                return;
            };
            let frame_bytes = (usize::try_from(w).unwrap_or(0))
                .checked_mul(usize::try_from(h).unwrap_or(0))
                .and_then(|px| px.checked_mul(4));
            let Some(frame_bytes) = frame_bytes else {
                return;
            };
            if bytes.len() < frame_bytes {
                // Frame inconsistente: descartado en silencio (log por panel-fps
                // era spam); el siguiente buffer normalmente ya viene bien.
                return;
            }
            // Publicar sobrescribiendo (R3): sin cola, sin descartes con log,
            // sin bloquear al compositor; el lector toma el último fresco.
            publish_mailbox.publish((w, h, layout, bytes[..frame_bytes].to_vec()));
        })
        .register()
        .ok();

    let params = enum_format_pod();
    if params.is_empty() {
        println!("capture: enum_format no serializable");
        return;
    }
    let Some(pod) = pipewire::spa::pod::Pod::from_bytes(&params) else {
        println!("capture: enum_format pod inválido");
        return;
    };
    if stream
        .connect(
            pipewire::spa::utils::Direction::Input,
            Some(node_id),
            stream_flags(),
            &mut [pod],
        )
        .is_err()
    {
        println!("capture: stream.connect al nodo {node_id} falló");
        return;
    }

    // Tick de parada: `run` se queda hasta `quit`; el timer re-consulta
    // `running` y llama `quit` cuando la sesión pidió cierre. `quit` es
    // thread-safe (pw_main_loop_quit).
    let timer = tloop.loop_().add_timer({
        let tloop = pipewire::main_loop::MainLoopRc::clone(&tloop);
        move |_| {
            if !running_timer.load(Ordering::Relaxed) {
                tloop.quit();
            }
        }
    });
    let _ = timer.update_timer(Some(WORKER_TICK), Some(WORKER_TICK));

    tloop.run();
    drop(timer);
    drop(listener);
}

/// POD de `enum_format`: oferta los formatos de 4 B/px negociables y deja
/// que el compositor fije tamaño (nativo del monitor) y framerate (fps del
/// panel). Vacío solo si la serialización falla (caller lo trata como
/// salida limpia).
fn enum_format_pod() -> Vec<u8> {
    use pipewire::spa::param::ParamType;
    use pipewire::spa::param::format::{FormatProperties, MediaSubtype, MediaType};
    use pipewire::spa::param::video::VideoFormat;
    use pipewire::spa::pod::property;
    use pipewire::spa::pod::serialize::PodSerializer;
    use pipewire::spa::pod::{Object, Value};
    use pipewire::spa::utils::{Fraction, Rectangle, SpaTypes};
    use std::io::Cursor;

    let obj = Object {
        type_: SpaTypes::ObjectParamFormat.as_raw(),
        id: ParamType::EnumFormat.as_raw(),
        properties: vec![
            property!(FormatProperties::MediaType, Id, MediaType::Video),
            property!(FormatProperties::MediaSubtype, Id, MediaSubtype::Raw),
            property!(
                FormatProperties::VideoFormat,
                Choice,
                Enum,
                Id,
                VideoFormat::BGRx,
                VideoFormat::RGBx,
                VideoFormat::xRGB,
                VideoFormat::RGBA
            ),
            property!(
                FormatProperties::VideoSize,
                Choice,
                Range,
                Rectangle,
                Rectangle {
                    width: 640,
                    height: 480
                },
                Rectangle {
                    width: 1,
                    height: 1
                },
                Rectangle {
                    width: 16_384,
                    height: 16_384
                }
            ),
            property!(
                FormatProperties::VideoFramerate,
                Choice,
                Range,
                Fraction,
                Fraction { num: 60, denom: 1 },
                Fraction { num: 0, denom: 1 },
                Fraction { num: 120, denom: 1 }
            ),
        ],
    };
    match PodSerializer::serialize(Cursor::new(Vec::new()), &Value::Object(obj)) {
        Ok((cursor, _)) => cursor.into_inner(),
        Err(_) => Vec::new(),
    }
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    /// El POD de `enum_format` se serializa no-vacío: PipeWire lo exige para
    /// la negociación; sin él el stream no conecta.
    #[test]
    fn enum_format_pod_is_serializable() {
        let pod = enum_format_pod();
        assert!(!pod.is_empty());
    }

    /// Contrato con el emisor (video.rs): el frame entregado es fila-mayor
    /// 4 B/px exacto (mismo contrato que `xcap::image.into_raw` en el
    /// fallback screenshot), de modo que `rgb8_scaled` sirve para ambas.
    #[test]
    fn frame_bytes_match_rgb8_scaled_contract() {
        let (w, h) = (64_u32, 32_u32);
        let frame = vec![0_u8; (w * h * 4) as usize];
        let rgb = crate::rgb8_scaled(&frame, w, h, 16, 8, crate::ChannelLayout::Rgba);
        assert_eq!(rgb.len(), 16 * 8 * 3);
    }

    /// El buzón publica sobrescribiendo y el lector espera un frame NUEVO:
    /// un frame consumido no se reentrega (anti-lag), y la publicación en
    /// slot lleno nunca fracasa (anti-spam).
    #[test]
    fn mailbox_publish_overwrites_and_wait_consumes_once() {
        let mailbox = FrameMailbox::default();
        let f1 = (1_u32, 1_u32, crate::ChannelLayout::Bgrx, vec![0_u8; 4]);
        let f2 = (2_u32, 2_u32, crate::ChannelLayout::Bgrx, vec![0_u8; 4]);
        mailbox.publish(f1);
        mailbox.publish(f2); // sobrescribe f1 sin error
        let got = mailbox
            .wait_latest(Duration::from_millis(50))
            .expect("frame");
        assert_eq!(got.0, 2); // siempre el más reciente
        // Consumido: el siguiente intento expira sin frame nuevo.
        assert!(mailbox.wait_latest(Duration::from_millis(50)).is_none());
    }
}
