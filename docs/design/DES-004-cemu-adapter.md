# DES-004 — Adaptador Cemu bidireccional (CR-005/CR-006/CR-007)

| | |
|---|---|
| ID | DES-004 |
| Title | Realización del adaptador experimental Cemu bidireccional |
| Version | 0.3.0 |
| Status | `Draft` |
| Owner | Eng |
| Realizes | SPEC-004 §4/§7/§12; SPEC-006 §3/§7/§9/§12/§16 (CR-005/CR-006/CR-007) |
| Depends on | DES-003, ADR-007, ADR-008 |
| Last updated | 2026-10-18 |

## 1. Overview

El cliente C++ del adaptador se inicia al solicitarlo con “Iniciar conexión” (solo con título activo) o cuando la preferencia persistente “Conexión automática” está activada al arrancar el título. En el inicio manual, Cemu consulta primero el QR vigente por TCP `127.0.0.1:8423`; si no responde una instancia, lanza `twinpad-server` desde PATH y reintenta la consulta por hasta 3 s. El endpoint devuelve el URI canónico y una matriz de módulos 0/1, ambos obtenidos de la misma entrada vigente del TokenStore. Cemu muestra la matriz en diálogo modal con zona silenciosa de cuatro módulos; al aceptar el diálogo solicita el adaptador y al cancelar no conecta. El listener usa un puerto dedicado enlazado exclusivamente a loopback, tiene timeout de lectura/escritura y responde a `GET_QR` con NDJSON `{uri, modules}`. La preferencia se guarda en la configuración de Cemu, false por defecto. Activarla mientras un título está activo inicia el cliente inmediatamente si no estaba solicitado; desactivarla no cierra una conexión o solicitud existente, y solo cambia el inicio automático de títulos posteriores. El proceso iniciado por Cemu permanece vivo al cerrar título; al cerrar Cemu se pregunta si detenerlo. Al terminar el título se detiene el cliente y se cierra la conexión. Su worker mantiene las dos conexiones localhost existentes, anuncia Cemu y reintenta la conexión primaria/audio cada 2 s cuando fue iniciado. La conexión primaria NDJSON recibe frames del server y reportes completos `input_report`; el Server PC escribe los reportes autorizados en el mismo socket del anuncio con framing línea por línea. El controlador VPAD aplica el estado Twinpad como fuente exclusiva mientras el cliente está conectado.

## 2. Fit with the architecture

- Components touched: Adaptador experimental Cemu y Servidor PC.
- Boundary rules respected: app → sesión autenticada → servidor → IPC localhost; Cemu no accede a la sesión LAN ni elude Pairing. El endpoint QR se enlaza a `127.0.0.1` solamente.
- New components introduced: endpoint TCP de consulta local del QR en el Servidor PC; `TwinpadClient` lanza/reutiliza y dibuja la respuesta en Cemu.

## 3. Domain model

- `TwinpadInputState`: última instantánea validada por secuencia recibida desde el server; se reinicia al abrir/cerrar una conexión.
- `Cemu adapter lifecycle`: título activo sin solicitud (espera, no conecta); iniciado manualmente o por preferencia auto-connect (conecta/reintenta, máximo 2 s); detenido al finalizar el título. Desactivar auto-connect no cancela una solicitud en curso.
- `twinpad_auto_connect`: preferencia persistente Cemu, inicial false.
- `VPADStatus_t`: recibe exclusivamente el estado Twinpad en player 0 mientras la conexión esté activa; controles locales no se combinan.

## 4. Interfaces

| Interface | Contract | Notes |
|---|---|---|
| Sesión → Server PC | SPEC-004 §4, `input_report` | valida rangos y orden por `seq` |
| Server PC → Cemu | SPEC-006 §7, NDJSON `input_report` terminado en LF | socket primary `127.0.0.1:8421` compartido con salida `game_frame`; escritura de línea atómica |
| Cemu → VPAD | SPEC-004 §4 / CR-005 §6 | botones homónimos, extras ignorados, triggers binarios, sticks/D-pad/touch/gyro directos |
| Cemu audio | SPEC-005 / CR-003 | socket `127.0.0.1:8422`, sin cambios de formato |
| Cemu consulta QR | SPEC-006 §12 / CR-007 | `GET_QR\n` a `127.0.0.1:8423`; respuesta NDJSON `{uri, modules}`; filas de igual ancho, caracteres `0`/`1`; solo loopback |

## 5. Data model and persistence

| Store | Schema | Migrations | Retention |
|---|---|---|---|
| Estado del adaptador | `TwinpadInputState` + último `seq` en memoria | Ninguna | Reiniciado al conectar/desconectar o salir del título |
| Propiedad de input server | `Option<SessionId>` en memoria | Ninguna | Hasta desconexión de la sesión |

## 6. Internal flows

```mermaid
sequenceDiagram
  participant UI as MainWindow
  participant C as TwinpadClient
  participant S as Server PC / GameRegistry
  participant A as Android session
  participant V as VPADController
  participant Q as QR IPC 127.0.0.1:8423
  alt usuario elige “Iniciar conexión”
    UI->>Q: GET_QR\n (inicia twinpad-server desde PATH si no responde)
    Q-->>UI: {uri, modules} del TokenStore vigente
    UI->>UI: mostrar QR en diálogo Cemu; cancelar aborta conexión
  else auto-connect habilitado al cargar título o usuario confirma QR
    UI->>C: RequestConnection / Start(capture=true)
  else preferencia deshabilitada
    UI->>C: SetTitleActive(true, false), permanece sin conexión
  end
  opt usuario habilita auto-connect con título activo y conexión no iniciada
    UI->>C: RequestConnection / Start(capture=true)
  end
  loop mientras título activo y conexión solicitada
    C->>S: game_announce por TCP localhost
    S->>C: líneas input_report con LF
    A->>S: input_report autenticado y seq creciente
    S->>C: misma línea NDJSON por socket primary
    C->>C: parsear línea completa y aplicar filtro seq
    V->>C: snapshot de estado Twinpad
    C-->>V: controles DRC
  end
  UI->>C: Stop al salir del título
  Note over UI,C: Desactivar auto-connect no desconecta ni cancela una conexión existente
```

- Transaction boundaries: cada mensaje NDJSON se escribe completo bajo el lock del registro y el receptor solo procesa líneas terminadas en LF.
- Consistency model: estado de control de último `seq` aceptado; la primera sesión que aporta input controla hasta que se desconecta.

## 7. Concurrency

| Concern | Approach |
|---|---|
| Lectura y escritura primary simultáneas | TCP full-duplex; el hilo InputLoop recibe y FrameLoop escribe bajo el mutex de socket |
| Inicio y parada | MainWindow posee auto/manual start y stop al salir del título; al desactivar auto-connect con cliente ya solicitado, no hay stop |
| Retry y parada del título | sockets se despiertan con shutdown al salir del título y el worker no bloquea el hilo UI |
| Acceso del VPAD al estado | snapshot por mutex corto, aplicado en el hilo de emulación |
| Frame/video y audio | mantienen colas/threads existentes y límite de 5 fps |

## 8. Error handling

| SPEC error | Mechanism |
|---|---|
| Servidor ausente o reiniciado | reconectar y re-anunciar cada 2 s mientras el título permanezca activo |
| EOF, framing inválido o error de socket | marcar desconectado, descartar buffer parcial, resetear estado de input y reintentar |
| `seq` repetido/antiguo | descartar el reporte sin alterar VPAD |
| Sin título activo | “Iniciar conexión” está deshabilitado; cerrar sockets al salir del título y no reintentar hasta nueva solicitud válida |
| Auto-connect desactivado | no iniciar al comenzar títulos; solicitud manual sigue disponible |
| Auto-connect desactivado mientras conexión activa | conservar la conexión actual y aplicar la preferencia al próximo título |
| No existe servidor local | lanzar `twinpad-server` desde PATH y reintentar QR hasta 3 s; si falla, mostrar error y no solicitar adaptador |
| QR IPC inaccesible/malformado | descartar respuesta, mostrar error observable y no solicitar conexión |
| Cierre Cemu con proceso servidor propio | preguntar si detener el proceso iniciado por Cemu; no detener una instancia reutilizada |

## 9. Caching

| Cache | Invalidation | Failure mode |
|---|---|---|
| Último `TwinpadInputState` | nuevo socket, EOF o stop | regresar a controles locales luego de desconectar |
| Buffer NDJSON parcial | LF procesa línea; límite excedido/EOF lo descarta | desconectar y reintentar |

## 10. Algorithms

| Problem | Algorithm | Complexity | Justification |
|---|---|---|---|
| Enmarcado NDJSON | acumular bytes, extraer cada línea LF y parsear JSON completo | O(n) bytes | TCP no preserva límites de mensaje |
| Mapeo sticks | `int8 / 127.0f`, invertir Y para convención VPAD | O(1) | conserva rango −1…1 |
| Mapeo de touch | escalar 0…65535 a rectángulo TP del VPAD; al soltar conservar posición | O(1) | coincide con el rango de touch definido |

## 11. Patterns applied

- **Snapshot de estado:** el VPAD no toma locks de red ni depende del framing/socket.

## 12. Traceability

| SPEC aspect | Where realized | Test |
|---|---|---|
| SPEC-004 §4 touch/orientación y serialización | `core::input`, Kotlin `InputCodec` | TEST-043 |
| SPEC-006 forwarding de input_report a Cemu vivo por NDJSON/LF | `GameRegistry::send_cemu_input` | TEST-044 |
| SPEC-006 title lifecycle, reconexión, parseo y VPAD exclusivo | `TwinpadClient`, `VPADController` | TEST-045 (build Cemu; validación E2E física pendiente) |
| CR-006 auto-connect opt-in persistente, inicio manual y menú | `wxCemuConfig`, `MainWindow`, `TwinpadClient` | TEST-046 (build y verificación de código; UI/config manual pendiente) |
| CR-007 consulta loopback del URI/matriz QR vigente y presentación antes de conectar | `run_qr_ipc_listener`, `TwinpadClient::GetCurrentQr`, diálogo de `MainWindow` | TEST-047 (loopback Rust y build Cemu; smoke de ventana QR pendiente título de prueba) |

## 13. Related decisions

- CR-004 — integración experimental Cemu y límite de captura 5 fps.
- CR-005 — retry 2 s tras inicio, fallback de video existente y mapeo directo.
- CR-006 — preferencia persistente opt-in, start manual y menú de dos opciones.
- CR-007 — inicio/reutilización del Servidor PC desde Cemu, consulta QR solo loopback y pregunta por detener el proceso propio al cerrar.
- ADR-007 — primera ruta de inputs por sesión LAN autenticada.
- ADR-008 — IPC localhost TCP NDJSON.

## 14. Open questions

- Ninguna para el alcance aprobado por CR-005; validación física del adaptador permanece como gate de build/end-to-end.

## Definition of Done

- [x] Realizes SPEC-004 y SPEC-006 dentro del alcance CR-005.
- [x] Respeta la frontera sesión autenticada → servidor → IPC localhost.
- [x] Retry, desconexión, secuencia y framing descritos.
- [x] Auto-connect persistente opt-in y conexión manual descritos (CR-006).
- [x] Menú Twinpad descrito con “Iniciar conexión” y “Configuraciones”.
- [ ] Probar visualmente ventana de ajustes, persistencia y conexión en vivo en Cemu.
- [x] TEST-043/044 y TEST-046/047 registrados en trazabilidad.
- [ ] Build Cemu y prueba end-to-end en Cemu + app.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-18 | Product owner + Twinpad team | Diseño inicial para CR-005 aprobado |
| 0.2.0 | 2026-10-18 | Product owner + Twinpad team (CR-006) | Preferencia auto-connect opt-in, inicio manual, actualización en vivo y menú Twinpad |
| 0.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-007) | Inicio/reutilización del Servidor PC, QR vigente presentado en Cemu y gestión de ciclo del proceso propio |
