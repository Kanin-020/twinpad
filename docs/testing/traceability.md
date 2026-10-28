# Traceability Matrix — Twinpad

> The registry of all traceability links. Each row is written here once; documents reference IDs, this matrix is the complete map.
> Direction: `REQ → SPEC → DES → TEST`, plus `CR` changes and `ADR` justifications.

## Next free IDs

| Type | Next |
|---|---|
| TEST | TEST-089 |

> 💡 TEST IDs are reserved here and defined physically in the test code (see test-strategy.md §4).

## REQ → SPEC → DES → TEST

| REQ | SPEC | DES | TEST | Status | Notes |
|---|---|---|---|---|---|
| REQ-002 | SPEC-001 | DES-001 | TEST-001 | Approved | Flujo principal del Handshake (SPEC-001 §7) — implementado en `core::handshake` |
| REQ-002 | SPEC-001 | DES-001 | TEST-002 | Approved | E1 servidor no disponible (REQ-001 AC-3) — aspecto servidor: cierre tras rechazo verificado en loopback (`server/tests/loopback_integration.rs`); aspecto cliente en TEST-011 |
| REQ-002 | SPEC-001 | DES-001 | TEST-003 | Approved | E2 QR expirado (REQ-002 AC-3) — implementado en `core::crypto::is_expired` |
| REQ-002 | SPEC-001 | DES-001 | TEST-004 | Approved | E3 sin token / consumido / proof inválido (NFR-004, REQ-002 AC-2) — implementado en `core::handshake` + E3 por el wire en loopback |
| REQ-002 | SPEC-001 | DES-001 | TEST-005 | Approved | E3 segundo dispositivo con el mismo QR — implementado en `core::handshake` + R2 en `server::tokens` y por el wire |
| REQ-002 | SPEC-001 | DES-001 | TEST-006 | Approved | E4 mensaje malformado / schema desconocido — implementado en `core::message` + `core::qr` (E5) + E4 por el wire en loopback |
| REQ-002 | SPEC-001 | DES-001 | TEST-007 | Approved | Reanudación de sesión (SPEC-001 §8.2) — pendiente del registro de sesiones en `server/` |
| REQ-002 | SPEC-001 | DES-001 | TEST-008 | Approved | Revocación del emparejamiento (SPEC-001 §8.3) — `TokenStore::revoke` implementado y testeado; revocación por UI pendiente |
| REQ-002 | SPEC-001 | DES-001 | TEST-009 | Approved | R7: regeneración automática del QR — implementado en `TokenStore::ensure_fresh_qr` + listener del binario (unit + integración) |
| REQ-002 | SPEC-002 | DES-002 | TEST-010 | Approved | Flujo completo en la app: escaneo → Vinculado (§7) — implementado, proof verificado con HMAC independiente |
| REQ-002 | SPEC-002 | DES-002 | TEST-011 | Approved | E1 Servidor no disponible desde la app (§9) — implementado |
| REQ-002 | SPEC-002 | DES-002 | TEST-012 | Approved | E5 QR inválido + permiso de cámara denegado (§8.1/§9) — implementado |
| REQ-002 | SPEC-002 | DES-002 | TEST-013 | Approved | E2/E3 mensajes exactos desde `pair_error` (REQ-002 AC-3, §9) — implementado |
| REQ-002 | SPEC-002 | DES-002 | TEST-014 | Approved | Edge cases de UI: cancelar, segundo plano, código desconocido, reintento (§10) — implementado |
| REQ-004 | SPEC-003 | DES-003 | TEST-015 | Approved | Codec y relay de video; JPEG q80 medido en fixtures de 720p/1080p. CR-010 elimina captura/demo como fuente de producción; ver TEST-051/054 |
| REQ-004 | SPEC-003 | `-` | TEST-016 | Approved | Frame corrupto/tardío y edge cases de §10 — codec estricto E4 en `core::stream` + descarte en `StreamStateMachine` (app) |
| REQ-005 | SPEC-003 | `-` | TEST-017 | Approved | Reanudación tras corte breve < 10 s sin re-pairing (§8.2, REQ-005 AC-2) — implementada en el server (sesión persistente); caso de red en banco físico |
| REQ-003 | SPEC-004 | `-` | TEST-018 | Approved | Flujo táctil → juego y orden por `seq` (SPEC-004 §7, REQ-003 AC-2) — transporte inicial: sesión LAN de SPEC-001 (OQ §17); superficie táctil completa con sticks analógicos (−127…127, centro al soltar §10) y triggers analógicos (0…255) en `GamepadView` + máquina pura `GamepadSurfaceState` (6 tests JVM) |
| REQ-003 | SPEC-004 | `-` | TEST-019 | Approved | Rumble (AC-3) y edge cases §10 — postergado a la iteración de rumble (requiere canal PC→app) |
| REQ-005 | SPEC-005 | `-` | TEST-020 | Approved | Flujo principal del audio (SPEC-005 §7, REQ-005 AC-1) — codec estricto `audio_start/chunk/stop` en `core::audio` (9 tests, límites §4: 16 KiB, s16le, rate 8k–192k); emisor `server::audio` con fragmentación y ritmo real (4 tests) + verificación punta a punta por TCP real (86 chunks en 2 s a 44.1 kHz); receptor en la app: `AudioStateMachine` A0/A1/A2 con jitter 500 ms fijo (§6, OQ §17), descarte por `seq`/corrupción (§9) y reproducción vía `AudioPlayback` (AudioTrack) — 9 tests JVM |
| REQ-004 | SPEC-003 | `-` | TEST-025 | Approved | Pipeline Android: frames obsoletos descartados antes de parsear JSON/base64/decodificar imagen |
| REQ-005 | SPEC-005 | `-` | TEST-024 | Approved | Adaptador de audio: continuidad de chunks tras reconfigurar formato y detener un stream; pista de salida reemplazada sin cerrar el consumidor de comandos |
| REQ-005 | SPEC-005 | `-` | TEST-035 | Approved | Fuente PCM ausente: el servidor no crea emisor ni anuncia audio, por lo que Android permanece en silencio (§3/§5) |
| REQ-006 | SPEC-006 | `-` | TEST-036 | Draft | Protocolo/SDK PCM: callback en hilo dedicado, segunda conexión localhost `game_audio_*`, validación y cola drop-old de ocho chunks; `sdk::tests::audio_queue_drops_oldest_and_drains_before_finish` y `core::game_audio` codec |
| REQ-005 | SPEC-005 | `-` | TEST-037 | Draft | Forwarding del juego seleccionado a `audio_start/chunk` (`server` binario test `selected_game_audio_is_forwarded_to_the_session`) |
| REQ-006 | SPEC-006 | `-` | TEST-038 | Draft | Adaptador Cemu CR-004 histórico; supersedido por CR-010 hasta 60 fps y luego por CR-029 (sin límite: fps solo como metadata); relay seleccionado |
| REQ-005 | SPEC-005 | `-` | TEST-039 | Draft | Rechazo de PCM sin juego/capability, fuente duplicada y forwarding PCM por TCP loopback (`server/tests/sdk_audio_integration.rs`) |
| REQ-005 | SPEC-005 | `-` | TEST-040 | Draft | Writer de sesión: cada mensaje de video precede audio pendiente en el límite de mensaje (CR-003), `server` binario test `session_writer_sends_video_before_queued_audio` |
| REQ-003 | SPEC-004 | `-` | TEST-043 | Draft | Campos touch DRC normalizados en `input_report` (CR-005); ver TEST-083 para el snapshot inercial vigente |
| REQ-006 | SPEC-006 | DES-004 | TEST-044 | Draft | Reenvío de línea NDJSON desde el Server PC al adaptador Cemu vivo (`server::games::tests::cemu_input_is_forwarded_to_live_adapter_only`) |
| REQ-006 | SPEC-006 | DES-004 | TEST-045 | Draft | Adaptador Cemu: ciclo por título, reconexión, parser y mapeo/manejo exclusivo al VPAD (CR-005) |
| REQ-006 | SPEC-006 | DES-004 | TEST-046 | Draft | Aceptación Cemu: preferencia opt-in persistente/false por defecto, auto-connect inmediato al activarlo, no desconexión al desactivarlo, inicio manual solo con título y menú exacto “Iniciar conexión”, “Configuraciones” (CR-006); build verificado, prueba UI/config en Cemu pendiente |
| REQ-006 | SPEC-006 | DES-004 | TEST-047 | Draft | `server/src/main.rs::tests::cemu_qr_ipc_returns_current_uri_and_matrix_only_on_loopback`: listener exclusivo `127.0.0.1`, consulta `GET_QR` y respuesta con URI canónico/matriz del TokenStore vigente; Cemu inicia/reutiliza twinpad-server desde PATH, presenta la imagen antes de conectar y pregunta al cerrar solo por el proceso que lanzó (CR-007; Cemu build verificado, smoke de ventana requiere título de prueba) |
| REQ-006 | SPEC-006 | DES-004 | TEST-048 | Draft | Aceptación CR-008 histórica: DRC publicado internamente sin ventana separada; el límite de 5 fps fue supersedído por CR-010 (hasta 60 fps) y luego por CR-029 (sin límite); build verificado, E2E con título real pendiente |
| REQ-006 | SPEC-006 | DES-004 | TEST-049 | Draft | El server identifica un adaptador Cemu vivo antes del primer frame para esperar el DRC sin elegir desktop/demo y habilitar desde el inicio la fuente de audio Cemu (`server::games::tests::live_cemu_is_detected_before_first_frame`) |
| REQ-006 | SPEC-006 | DES-004 | TEST-050 | Draft | La respuesta loopback del QR informa `cemu_live`; Cemu se registra y espera confirmación antes de presentar QR y revoca la solicitud manual al cancelar (CR-009) |
| REQ-004/005/006 | SPEC-003/005/006 | DES-004 | TEST-051 | Draft | `core::game::GameMsg` valida NDJSON estricto `game_select`/`game_selected`; `server::video::tests::selected_game_stream_relays_only_across_confirmed_source_boundaries` verifica silencio sin selección, límites stream_stop/stream_start y fn reiniciado al cambiar fuente. Audio seleccionado: TEST-037; E2E físico pendiente (CR-010) |
| REQ-003/006 | SPEC-004/006 | DES-004 | TEST-052 | Draft | `server::tests::selected_plugin_receives_initial_and_latest_input_snapshot_via_sdk_polling`: entrega de snapshot inicial + cambio posterior por TCP localhost y coalescing observable con `TwinpadGame::poll_input()` (CR-010) |
| REQ-004/005/006 | SPEC-002/003/005/006 | DES-004 | TEST-053 | Draft | Codec estricto, autoelección única y lista para varias fuentes (`GameListCodecTest`) (CR-010) |
| REQ-004/005/006 | SPEC-002/006 | DES-004 | TEST-055 | Draft | La lista de liveness nueva elimina selección/pedido obsoleto y solicita elección explícita entre fuentes sin selección vigente; conserva una selección todavía viva (`GameListCodecTest.removed_selection_is_cleared_before_choosing_from_the_updated_list`) (CR-010) |
| REQ-004/005/006 | SPEC-003/005/006 | DES-004 | TEST-054 | Draft | Cemu publica DRC/GamePad internamente sin imponer ritmo (30/60 fps históricos hasta CR-029); Server no captura escritorio ni genera demo (CR-010) |
| REQ-006 | SPEC-006 | `-` | TEST-026 | Approved | Frames `rgb8` del SDK se codifican como JPEG q80 por el servidor antes de emitirse con `fmt=jpeg`; caso JPEG preexistente permanece JPEG, wire contract SPEC-003 sin cambios |
| REQ-004 | SPEC-003 | DES-003 | TEST-027 | Approved | Codec Rust/Kotlin para `video_patch`: `fn` wire, validación de base y geometría de tiles (CR-002) |
| REQ-004 | SPEC-003 | DES-003 | TEST-028 | Approved | Diff RGB server y composición atómica Android; keyframe periódico/fallback CR-002; ruta Android compone sobre una sola copia del Bitmap y rechaza base inválida antes del trabajo de imagen; el server omite encode/base64 cuando los RGB no cambian (test de frame estático) |
| REQ-004 | SPEC-003 | DES-003 | TEST-031 | Draft | Encoder JPEG SIMD: frame q80 decodificable con dimensiones y color coherentes; wire `fmt=jpeg` sin cambios |
| REQ-004 | SPEC-003 | `-` | TEST-032 | Draft | Escalado RGB pre dimensionado preserva layouts de canal, nearest-neighbor y relleno negro ante buffer corto |
| REQ-006 | SPEC-006 | `-` | TEST-033 | Draft | Frames RGB publicados por el SDK se comparten sin clonar el buffer al seleccionar la fuente de stream |
| REQ-004 | SPEC-003 | DES-003 | TEST-029 | Draft | Regresión instrumentada: Framebuffer publicado sigue legible tras reemplazo y reset (Bitmap en Android real) |
| REQ-004 | SPEC-003 | DES-002 | TEST-086 | Draft | CR-025: `videoContentRect` — `contain` conserva la relación de aspecto con barras y `expand` ocupa todo el contenedor; unitario Android, prueba física pendiente |
| REQ-003 | SPEC-004 | DES-002 | TEST-087 | Draft | CR-028: Test JVM `PhysicalGamepadAlgoTest` — mapper puro del mando físico conectado al teléfono (bits canónicos, triggers L2/R2, teclas D-pad con diagonales y anulaciones, hat 0–7, zona muerta 0.1) y fusión por elemento con la superficie táctil (reposo físico no pisa, buttons OR, touch/motion intactos); integración real de eventos requiere dispositivo físico |
| REQ-004 | SPEC-003 | DES-003 | TEST-030 | Draft | FPS de render separado de FPS recibido (CR-029: el server ya no impone fps — se sigue el ritmo del juego); keyframes periódicos estáticos se contabilizan como presentación, no 0 FPS; al iniciar un stream nuevo se reinicia la época de `fn` sin perder métricas de audio |
| REQ-004 | SPEC-003 | DES-003 | TEST-034 | Draft | Diagnósticos del stream: gaps de `fn`, rechazos y promedios de duración por etapa |
| REQ-006 | SPEC-006 | `-` | TEST-021 | Approved | Detección automática y liveness (SPEC-006 §7, REQ-006 AC-1) — transporte: TCP localhost NDJSON (ADR-008); codec en `core::game` + registro en `server::games` + SDK Rust |
| REQ-002 | SPEC-001/002 | DES-002 | TEST-056 | Passed (2026-10-19, Android físico; intermittent retry failure) | Corrida final `artifacts/test-results/device-e2e/20261019T054326Z-577198`: Pairing autenticado, U4 y selección Cemu en 268 ms. El intento `20261019T053912Z-570870` falló con `SessionEnded` antes de confirmar Linked+selección; causa no determinada |
| REQ-004/006 | SPEC-003/006 | DES-004 | TEST-057 | Functional passed; performance pending | Corrida final: Cemu Framebuffer decodificado y drawable visible, 1920×1080; 7 frames progresivos/2 s (~3.77 fps recibido, ~3.73 presentado), 3748 kbps, 0 gaps/rechazos. Otra corrida observó ~0.98 fps a 854×480. Ambas quedan por debajo de 30/60 fps; latencia y reproducción PCM sincronizada no medidas. Encoder bench local bajo `docs/engineering/2026-10-19-cemu-twinpad-60fps.md` (no representa rendimiento objetivo; histórico de fps config en CR-010/CR-029) |
| REQ-003/006 | SPEC-004/006 | DES-004 | TEST-058 | Transport passed (2026-10-19; intermittent retry failure) | Corrida final: proxy observó 567 `input_report` en orden, 2 estados pressed y 1 transición pressed→released; guardó solo metadatos touch. Verifica transporte, no efecto dentro del juego. El reintento fallido no confirmó Pairing; evidencia en run `artifacts/test-results/device-e2e/20261019T054326Z-577198` |
| REQ-006 | SPEC-006 | `-` | TEST-021 | Approved | AC-2: el juego publica frames de Segunda pantalla vía SDK (`TwinpadGame::push_frame` → `game_frame`) y el server los retiene como fuente del Stream de SPEC-003 (se emiten como `video_frame`); liveness del contenido (invariante §14: el frame muere con el juego) — `core::game::GameMsg::GameFrame` + `server::games::GameRegistry::store_frame/preferred_source` + `server::video::run_game_stream` + 4 tests de integración loopback en `server/tests/sdk_frames_integration.rs` |
| REQ-007 | SPEC-007 | `-` | TEST-022 | Draft | Carga/activación/rechazo de Config externa (REQ-007 AC-1..3) — modelo y normalización implementados en `android surface/ControlProfileCodec.decodeExternalConfig` (validación §4/§9); activación desde la app pendiente de DES |
| REQ-008 | SPEC-008 | `-` | TEST-023 | Draft | Edición, carga automática y conflicto de superficie (REQ-008 AC-1..3) — modelo puro + validación + persistencia por juego (`surface/ControlProfile*`, 9 tests JVM) y superficie editable `ui/ProfileSurface` (drag/mover/perfiles XML-editables); UI migrada a Views XML (layouts/drawables editables en Android Studio, pedido del owner) |
| REQ-002/003 | SPEC-002/004 | DES-002 | TEST-059 | Draft | CR-011: PairingActivity en landscape habitual fijo y fullscreen inmersivo, y remapeo de ejes de orientación al marco de pantalla; instrumentación Android `ScreenOrientationTest` |
| REQ-003 | SPEC-004 | DES-002 | TEST-060 | Draft | Test JVM `GyroOrientationMapperTest.screen_relative_angles_preserve_the_wire_yaw_pitch_roll_order`: Android azimuth/pitch/roll ya remapeados al marco de pantalla se conservan en el orden wire yaw/pitch/roll; el giro de azimuth no se convierte en roll |
| REQ-002 | SPEC-002 | DES-002 | TEST-061 | Draft | Pruebas instrumentadas Android `SettingsDrawerTest`: el drawer de configuración excede el viewport en vertical y permite llegar a sus controles inferiores mediante desplazamiento; también verifica persistencia de preferencias al recargar `SharedPreferences`. |
| REQ-004/005/006 | SPEC-005/006 | DES-004 | TEST-062 | Draft | CR-012: banco físico debe confirmar audio TV local audible en PC y PCM estéreo DRC/GamePad audible en Android; probar desconexión Android sin silenciar TV, independencia de volúmenes y ausencia de mezcla. Regla documentada en SPEC-005 §8.4/R6, SPEC-006 R11 y DES-004 §4/§7/§12; audición física pendiente |
| REQ-006 | SPEC-006 | DES-004 | TEST-064 | Draft | CR-013: `InputManager_twinpad_vpad_test` verifica crear un VPAD temporal para Player 1 solo cuando falta, no duplicar durante conexión, dejar intacto el VPAD configurado, restaurar asignación previa/reemplazo al desconectar y no persistir el temporal; compilado e incluido en Cemu debug build (TEST-064 assertions no ejecutadas; smoke físico pendiente) |
| REQ-006 | SPEC-006 | DES-004 | TEST-065 | Draft | `TwinpadClient_base64url_test`: casos de borde Base64URL y exactitud de framebuffer 720p contra encoder de referencia; agregado al binario debug recompilado. La ejecución del arranque GUI aislado no alcanzó UnitTests antes del timeout; assertions runtime pendientes |
| REQ-008 | SPEC-008 | `-` | TEST-063 | Draft | Instrumentación Android `ProfileSurfaceRenderingTest`: crea y dibuja la superficie con perfil default y comprueba el control visible y el recurso XML editable `touch_button`; junto a TEST-023 (perfil/modelo) |
| REQ-005 | SPEC-005 | `-` | TEST-066 | Draft | Test JVM `AudioPlaybackTest`: el volumen local se aplica al track activo y permanece efectivo al reproducir chunks/recrear la salida (no afecta el PCM wire) |
| REQ-004 | SPEC-003 | DES-004 | TEST-068 | Draft | Test Rust `selected_game_stream_applies_runtime_resolution_and_fps_settings`: `video_config` runtime restringe el Framebuffer publicado y anuncia dimensiones con fuente SDK real (fps declarado solo como metadata, sin ritmo impuesto — CR-029); CR-027/CR-030 añaden el reenvío a Cemu de `bitrate_kbps` (1.000–30.000, default 8.000, `TWINPAD_VIDEO_BITRATE_KBPS`) y `w/h` con clamp 16–3840×16–2160 — `server::games::tests::cemu_video_config_forwarding_includes_resolution_and_bitrate` |
| REQ-008 | SPEC-008 | `-` | TEST-069 | Draft | Test JVM `AndroidSettingsStoreTest`: contrato `video_config` (CR-027: incluye `bitrate_kbps`; slider del drawer 1.000–30.000; CR-029: sin fps en el drawer), valores default y retención del porcentaje de volumen local; persistencia UI compartida con TEST-061 |
| REQ-004 | SPEC-003 | DES-004 | TEST-070 | Draft | Test Rust `selected_stream_resolution_scales_rgb_and_preserves_aspect_ratio`: redimensionamiento al límite configurado manteniendo aspecto y tamaño RGB correcto |
| REQ-004 | SPEC-003 | DES-003 | TEST-071 | Draft | Tests `core::media_crypto` y `MediaCryptoTest`: HMAC-SHA256/RFC4231, vectores compartidos HMAC/HKDF Rust↔Kotlin, framing length/counter, AES-GCM roundtrip, tag inválido, replay/salto y oversized record (CR-014) |
| REQ-004 | SPEC-003 | DES-003 | TEST-072 | Draft | `server::media::tests`: loopback TCP con challenge-response HMAC, espera de selección/stream_start, relay de IDR+P autenticados en counters consecutivos y cierre con revocación; proof inválido se cierra sin respuesta (CR-014) |
| REQ-004/006 | SPEC-003/006 | DES-004 | TEST-073 | Draft | `TwinpadClient_h264_encoder_test` prueba el encoder OpenH264 real: SPS/PPS + IDR inicial y tras reset, perfil Constrained Baseline nivel 4.2 y timestamps de captura crecientes (CR-014/CR-015); validación IPC autenticada pendiente de integración |
| REQ-004 | SPEC-003 | DES-003 | TEST-074 | Draft | Instrumentación `H264SurfaceDecoderTest` con MediaCodec y Surface real: ignora P-frame previo a IDR y hasta nuevo IDR tras discontinuidad/cambio de dimensiones; verifica cerrar y recrear el decoder conectado a la misma Surface durante reconexión. `MediaCryptoTest` verifica por socket local handshake/HKDF/AES-GCM y no entrega registro manipulado; E2E físico instrumentado asociado a TEST-056/057 (CR-014). `StreamCodecTest.stream_start_accepts_h264_media_contract…` (TEST-027 bloque) añade cobertura CR-029: `fps` 0 (unlimited) y 255 aceptados como metadata, −1/256 rechazados como inválidos |
| REQ-004 | SPEC-003 | DES-004 | TEST-075 | Draft | `VulkanRenderer_twinpad_readback_test` verifica selección del readback terminado más reciente, slots no terminados/libres y el sentinel cuando el anillo tiene menos de tres buffers asignados; Cemu compara ese sentinel con la capacidad del array para evitar acceso fuera de rango. El E2E de FPS requiere medir en dispositivo físico |
| REQ-003 | SPEC-004 | DES-004 | TEST-077 | Superseded by CR-021 | `TwinpadOrientationMapper_test` verificaba orientación absoluta, ganancia 0.5× y derivación gyro por diferencias; ya no representa el contrato de movimiento activo |
| REQ-003 | SPEC-002/004 | DES-002/004 | TEST-078 | Draft | `GyroMonitorRenderingTest` verifica que el monitor Android aparece apagado por defecto, persiste la preferencia opt-in y dibuja el rectángulo, ejes y valores yaw/pitch/roll; también afirma la paleta semántica yaw verde, pitch rojo, roll azul (CR-016/018) |
| REQ-006 | SPEC-006 | DES-004 | TEST-079 | Draft | `TwinpadGyroMonitor_test` verifica activación opt-in por defecto apagada y mapeo de los ejes recibidos a VPAD; aceptación GUI debe verificar ventana externa redimensionable (640×480 inicial, 480×360 mínimo), datos en vivo/estado de espera y persistencia al cerrar (CR-016/CR-017) |
| REQ-003 | SPEC-004 | DES-002 | TEST-080 | Superseded by CR-021 | El test de orientación absoluta reportada fue sustituido: la orientación queda local al monitor y el wire transporta sensores físicos |
| REQ-003 | SPEC-004 | DES-004 | TEST-081 | Superseded by CR-021 | La ganancia VPAD 0.5× sobre orientación absoluta fue reemplazada por gyro rad/s ×0.9 y fusión AndroidDSU→VPAD |
| REQ-004/006 | SPEC-003/006 | DES-004 | TEST-082 | Draft | `TwinpadClient_hardware_encoder_test` verifica opt-in apagado por defecto, inicialización hardware oportunista con fallback OpenH264 y reinicio al cambiar la preferencia; mantiene salida AVC Baseline Level 4.2, Annex B, SPS/PPS + IDR (CR-020). Prueba real con GPU/backend disponible y aceptación GUI/config pendientes |
| REQ-003 | SPEC-004 | DES-002/004 | TEST-083 | Draft | `ProfileGyroControllerTest` y `InputCodecTest`: combina las muestras más recientes de ambos sensores con timestamp monotónico, omite `motion` si falta una muestra y serializa el snapshot inercial de CR-021 desde Android |
| REQ-003 | SPEC-004 | DES-002/004 | TEST-085 | Draft | `ProfileGyroControllerTest.sensor_vectors_use_reference_frame`: `remapAccelerationVector` y `remapGyroscopeVector` aplican el marco AndroidDSU (accel `(-x,-z,y)`, gyro `(x,-z,y)`) (CR-022). Ejecución unitaria Android; prueba física pendiente |
| REQ-003 | SPEC-004 | DES-004 | TEST-084 | Draft | `TwinpadMotionMapper_test` afirma el mapeo de gyro/aceleración y delta timestamp AndroidDSU→DSU; `TwinpadGyroMonitor_test` verifica muestras recibidas y estado de fusión. Build Cemu verificado; ejecución de las aserciones integradas y prueba física del VPAD pendientes |
| REQ-004 | SPEC-003 | DES-004 | TEST-088 | Draft | `TwinpadClient_drc_scale_test` (Cemu, TEST binario debug): `ScaleRgb` nearest-neighbor (identity 2×2→2×2, shrunk 2×2→1×1 muestrea top-left, sin upscale a 4×4, inválido w×h×3 → vacío) y `SetCaptureResolution` (impar → par inferior, 0/0 → nativo); CR-030. Integración en el pipeline real (`FrameLoop`) pendiente de banco físico |

## NFR verification

| NFR | Verified by | Method (test / monitoring) | Threshold |
|---|---|---|---|
| NFR-001 | `-` | TEST de performance (captura óptica, banco de pruebas) | p95 ≤ 60 ms |
| NFR-002 | `-` | TEST de performance (inyección HID sintética) | p95 ≤ 15 ms |
| NFR-003 | `-` | TEST de monitoreo de recursos + revisión | ≤ 2 % CPU idle |
| NFR-004 | `-` | TEST de seguridad (rechazo sin token) | 100 % rechazo |
| NFR-005 | `-` | TEST en dispositivo físico | ≤ 25 % CPU @ 30 fps |
| NFR-006 | `-` | TEST de concurrencia (2 dispositivos) | degradación ≤ 10 % |
| NFR-007 | `-` | TEST de batería (Battery Historian) | ≤ 20 %/h |

## Acceptance criteria coverage

| REQ | AC | Verified by |
|---|---|---|
| REQ-001 | AC-3 | TEST-002 |
| REQ-002 | AC-1 | TEST-001 |
| REQ-002 | AC-2 | TEST-004, TEST-005 |
| REQ-002 | AC-3 | TEST-003 |

## ADR links

| ADR | Justifies / affects |
|---|---|
| ADR-001 | ARCH §2 (stack Kotlin/Rust) |
| ADR-002 | ARCH §1, §4 (transportes) |
| ADR-003 | ARCH §2, §6 (HID nativo) |
| ADR-004 | ARCH §4, §7 (pairing QR) |
| ADR-005 | ARCH §2, §4 (framebuffers) |
| ADR-006 | ARCH §2, §6 (modelos de plugin) |
| ADR-007 | SPEC-004 §17 (transporte inicial de inputs por sesión LAN) |
| ADR-008 | SPEC-006 §4/§17 (IPC del SDK por TCP localhost NDJSON) |

## Change history

| CR | Changes | Date | Status |
|---|---|---|---|
| CR-001 | SPEC-003 v1.1.0: `fmt=jpeg` (compresión del stream para 720p/1080p a 30/60 fps) | 2026-10-08 | Applied |
| CR-018 | SPEC-002/004 y DES-002/004: Android envía la orientación absoluta mostrada; ambos monitores usan yaw verde, pitch rojo, roll azul; TEST-078/080 | 2026-10-22 | Approved / in execution |
| CR-019 | SPEC-004/DES-004: escala gyro Cemu→VPAD a 0.5×; comportamiento supersedido por CR-021; TEST-077/081 históricos | 2026-10-22 | Superseded by CR-021 |
| CR-020 | SPEC-003 v1.5.0, ARCH y DES-004: preferencia Cemu opt-in para encoder H.264 hardware; fallback OpenH264 CPU, sin cambios de resolución ni wire; TEST-082 | 2026-10-23 | Approved / in execution |
| CR-027 | SPEC-002 1.6.0 y SPEC-003 1.8.0: bitrate objetivo H.264 configurable (drawer 1.000–30.000 kbps, `TWINPAD_VIDEO_BITRATE_KBPS`, `video_config.bitrate_kbps` reenviado a Cemu, encoder reinicia); TEST-068/069 actualizados | 2026-10-27 | Applied |
| CR-028 | SPEC-004 1.9.0: mando físico conectado al teléfono — mapper puro (keycodes/sticks/triggers/hat, zona muerta 0.1) y fusión por elemento con la superficie táctil; TEST-087 | 2026-10-27 | Applied (owner-requested) |
| CR-029 | SPEC-002 1.7.0, SPEC-003 1.9.0 y SPEC-006 1.15.0: emisión sin límite de fps — drawer sin selector (resolución/bitrate/HUD), `video_config`/wire sin fps ajustable (fps del server u8 metadata, 0 = unlimited), server sin pacing, Cemu sin clamp ni pacing al ritmo del juego, timestamps de captura reales; TEST-030/038/048/054/057/068/069/074 actualizados | 2026-10-28 | Applied (owner-requested) |
| CR-030 | SPEC-002 1.8.0, SPEC-003 1.10.0 y SPEC-006 1.16.0: resolución efectiva del GamePad — server reenvía w/h a Cemu, DRC se escala (nearest, sin upscale) antes del encode; TEST-068 actualizado y TEST-088 nuevo | 2026-10-28 | Applied (owner-requested) |
| CR-027 1.2.0 | Fix del bitrate observado: el rate-control del encoder de Cemu usa la cadencia REAL de emisión (nominal fijo 60 acotaba el stream a ~N/60 del target; tope medido ≈ 2.2 Mbps con target 30.000 a ~4–5 fps reales). Contrato sin cambios; verificación E2E del target en banco físico pendiente | 2026-10-28 | Applied |
| CR-024 | ADR-003 1.1.0 y decisions/_index.md: HID nativo diferido; giroscopio de terceros por SDK; sin tests ni cambio de código | 2026-10-27 | Applied |
| CR-023 | REQ-007 0.2.0, SPEC-004 1.8.0 y SPEC-006 1.14.0: Config externa sin movimiento; giroscopio para terceros solo por SDK; sin tests ni cambio de código | 2026-10-27 | Applied |
| CR-022 | SPEC-004 v1.7.0, DES-002 v0.3.3 y DES-004 v1.4.0: mapeo de ejes AndroidDSU solo en Android; Cemu convierte unidades y niega accel Y/Z como DSU; TEST-085 | 2026-10-26 | Applied |
| CR-021 | SPEC-004 y DES-002/004: muestras inerciales timestamped AndroidDSU→fusión `WiiUMotionHandler`/VPAD; TEST-083/084; supersede la orientación absoluta y el ajuste CR-019 | 2026-10-24 | Approved / in execution |
| — | `video_config` por sesión: resolución/fps/calidad JPEG en caliente desde el drawer de la app (parser del server + VM) | 2026-10-09 | Applied (owner-requested) |
| CR-002 | SPEC-003 v1.2.0: patches JPEG por tiles de 64 px, base_fn y keyframes periódicos; DES-003 y TEST-027/028 | 2026-10-17 | Applied |
| CR-003 | PCM de audio del juego vía SDK: canal IPC separado, callback y prioridad video/drop-old | 2026-10-17 | Approved |
| CR-004 | Adaptador experimental nativo Cemu para prueba semi-real | 2026-10-17 | Approved |
| CR-005 | Cemu title-scoped con input directo al VPAD; touch wire extendido, fallback de video conservado | 2026-10-18 | Approved |
| CR-006 | Auto-connect Cemu opt-in persistente, conexión manual y menú de dos opciones | 2026-10-18 | Approved |
| CR-007 | Cemu inicia/reutiliza Servidor PC desde PATH y presenta el QR vigente por interfaz exclusiva loopback | 2026-10-18 | Approved |
| CR-008 | Captura automática y oculta del DRC, independiente de Separate GamePad View, con límite de 5 fps | 2026-10-18 | Approved |
| CR-009 | Registrar Cemu y confirmar su presencia antes de mostrar QR; captura desktop solo en modo de pruebas | 2026-10-18 | Applied |
| CR-010 | Plugin como fuente A/V; selección Android por sesión, Server relay/adaptación, polling input y host gestiona servidor/QR. Dos corridas E2E Android+Cemu pasaron; un intento intermedio terminó en `SessionEnded`. Tasa medida 0.98–3.77 fps (según resolución 854×480/1920×1080), bajo 30/60 fps; PCM/A-V ±150 ms no medidos | 2026-10-18 | Applied; functional E2E passed, intermittent retry failure observed, performance/audio gates pending |
| CR-011 | Landscape habitual inmersivo y remapeo gyro al marco de pantalla; esquema wire sin cambios | 2026-10-18 | Applied |
| CR-012 | SPEC-005/006, ARCH y DES-004: TV permanece en salida local PC; PCM DRC/GamePad Cemu es el único audio enviado a Android, sin mezcla ni cambio wire; TEST-062 banco audible reservado | 2026-10-19 | Applied in docs/code; physical audio gate pending |
| CR-013 | SPEC-006/DES-004: crear VPAD temporal de Player 1 cuando Twinpad conecta solo si falta; no persistir ni alterar perfil local; TEST-064 | 2026-10-19 | Approved; implementation/build pending verification |
| — | Informe 2026-10-19: separate view DRC path review and 60 fps performance evidence/recommendations (`docs/engineering/2026-10-19-cemu-twinpad-60fps.md`); implementación Base64URL preasignado para reducir coste del IPC sin cambiar el wire | 2026-10-19 | Analysis/optimization; Cemu debug build/relink passed, TEST-065 runtime assertions and device E2E pending |

## Invariants of this matrix

1. Every Approved SPEC appears in ≥ 1 row of the first table.
2. Every REQ marked `Implemented` has all its ACs covered in the third table.
3. Every TEST ID used in any doc exists in the first or second table.
4. Every CR that changed a SPEC has a row in the change history.
5. Broken links in this matrix are release blockers.
