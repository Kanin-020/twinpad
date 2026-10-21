# DES-004 — Adaptador Cemu bidireccional (CR-005/CR-006/CR-007/CR-008/CR-009/CR-010/CR-012/CR-013/CR-014/CR-015)

| | |
|---|---|
| ID | DES-004 |
| Title | Realización del adaptador experimental Cemu bidireccional |
| Version | 0.9.0 |
| Status | `Draft` |
| Owner | Eng |
| Realizes | SPEC-003 §4–§16; SPEC-004 §4/§7/§12; SPEC-005 §8.4; SPEC-006 §3/§4/§7/§9/§12/§16 (CR-005/CR-006/CR-007/CR-008/CR-009/CR-010/CR-012/CR-013/CR-014/CR-015) |
| Depends on | DES-003, ADR-007, ADR-008 |
| Last updated | 2026-10-20 |

## 1. Overview

Cemu es la primera integración host del Plugin SDK. El host Cemu inicia `twinpad-server` desde PATH si no existe una instancia activa, o reutiliza la que responde; consulta el QR vigente en `127.0.0.1:8423`, inicia el anuncio `cemu` y espera `cemu_live=true` antes de presentar el QR. El SDK/Plugin es headless. Al cancelar el diálogo, Cemu cancela la solicitud manual y cierra sus conexiones; auto-connect opt-in existente permanece según CR-006.

Con sesión activa, Android selecciona el Plugin por `game_id`. Para Cemu, el readback DRC alimenta OpenH264 software (AVC Constrained Baseline Level 4.2, I420, sin B-frames; IDR con SPS/PPS); Cemu publica AU Annex B al IPC SDK y el Servidor las relaya cifradas sin transcodificar al Android que se autenticó por TCP 8425. Inputs y `stream_start/stop` siguen por el canal Pairing NDJSON. No se envía `session_key` a Cemu. Los callbacks de audio TV y DRC conservan las rutas locales/Android independientes de CR-012. Screenshots independientes de Cemu no cambian.

## 2. Fit with the architecture

- Components touched: Adaptador experimental Cemu y Servidor PC.
- Boundary rules respected: app → sesión autenticada → servidor → IPC localhost; Cemu no accede a la sesión LAN ni elude Pairing. El endpoint QR se enlaza a `127.0.0.1` solamente.
- New components introduced: endpoint TCP de consulta local del QR en el Servidor PC; `TwinpadClient` lanza/reutiliza y dibuja la respuesta en Cemu.

## 3. Domain model

- `TwinpadInputState`: último snapshot válido y secuencia recibido del Servidor; se reinicia al terminar la conexión del título.
- `Cemu host lifecycle`: inicia/reutiliza el Servidor PC, anuncia `cemu` y presenta QR; la conexión SDK se mantiene por título bajo las preferencias Cemu existentes.
- `twinpad_auto_connect`: preferencia persistente Cemu, false por defecto.
- `VPADStatus_t`: recibe el estado Twinpad más reciente mientras el Plugin Cemu es el destino seleccionado; controles locales no se combinan.
- `Temporary Player 1 VPAD`: InputManager crea un VPAD en memoria al conectarse Twinpad solo si no hay uno para Jugador 1; el objeto es propiedad temporal del adaptador, no se persiste y se retira al desconectar. Un VPAD existente nunca se reemplaza.
- `DRC source`: textura GamePad publicada por el adaptador a la tasa configurada del stream, máximo 60 fps; Separate GamePad View permanece independiente.

## 4. Interfaces

| Interface | Contract | Notes |
|---|---|---|
| Host Cemu → Servidor PC | Inicio/reutilización del proceso y consulta QR, CR-010 / SPEC-006 | QR loopback `127.0.0.1:8423`; espera `cemu_live=true` antes de mostrar |
| Android session → Servidor PC | SPEC-004 `input_report`; SPEC-006 `game_select` | sesión autenticada, selección por `game_id` |
| Servidor PC → Cemu Plugin | SPEC-006 `input_report` snapshot por NDJSON/LF | conexión primaria IPC; snapshot inicial + cada actualización aceptada |
| Cemu → Servidor PC | SPEC-006 anuncio + `game_frame(fmt=h264,ts_capture_us,idr)` | IPC TCP localhost NDJSON; AU Annex B Base64URL, timestamp monotónico de encoder e indicador IDR (CR-015) |
| Servidor PC → Android media | SPEC-003 v1.4.0 §§4.2–4.3 | listener TCP 8425, mutual HMAC, HKDF-SHA256, AES-256-GCM, counter por conexión |
| Cemu → codec | CR-014 / SPEC-003 §4.3 | OpenH264 software; Constrained Baseline L4.2, I420, sin B-frames, SPS/PPS cada IDR |
| Cemu salida TV → dispositivo de audio PC | callback TV de Cemu y `g_tvAudio` | Solo salida local PC; no se captura ni publica en Twinpad; independiente del estado Android (CR-012) |
| Cemu audio DRC → Servidor PC | SPEC-005 / SPEC-006 `game_audio_*` | PCM estéreo GamePad/DRC por canal IPC de audio existente; única fuente de Cemu que se retransmite a Android |
| Cemu salida GamePad DRC → dispositivo PC | callback DRC de Cemu y `g_padAudio` | Conserva salida local y control de volumen DRC; además del PCM IPC, no combinado con TV (CR-012) |
| Servidor PC → Android | SPEC-003 / SPEC-005 | únicamente si el Plugin Cemu está confirmado para la sesión |

## 5. Data model and persistence

| Store | Schema | Migrations | Retention |
|---|---|---|---|
| Estado del adaptador | `TwinpadInputState` + último `seq` en memoria | Ninguna | Reiniciado al conectar/desconectar o salir del título |
| Propiedad de input server | `Option<SessionId>` en memoria | Ninguna | Hasta desconexión de la sesión |

## 6. Internal flows

```mermaid
sequenceDiagram
  participant H as Cemu host
  participant Q as QR loopback
  participant S as Servidor PC
  participant A as Android session
  participant C as Cemu Plugin
  participant V as VPADController
  H->>S: iniciar o reutilizar twinpad-server
  H->>Q: GET_QR
  Q-->>H: URI, matriz, cemu_live
  H->>C: iniciar SDK/Plugin y esperar anuncio vivo
  C->>S: game_announce + game_frame + game_audio_* (PCM DRC/GamePad solamente)
  C->>C: callback TV → salida local del PC; callback DRC → salida local GamePad + publicación IPC
  H->>H: presentar QR vigente
  A->>S: game_select(game_id=cemu)
  S-->>A: game_selected(game_id=cemu)
  A->>S: input_report(seq creciente)
  S->>C: snapshot inicial y actualizaciones input_report
  C->>C: poll_input() consulta el snapshot más reciente
  C->>S: DRC GameFrame hasta 60 fps + PCM DRC
  S->>A: video/audio adaptados solo de Cemu
  S->>V: aplica snapshot al VPAD emulado
  Note over V: si falta VPAD Player 1, InputManager crea uno temporal en memoria
  Note over V: al desconectar retira solo el VPAD temporal; los perfiles no cambian
```

Audio/input/selección mantienen los contratos existentes. El video usa el framing cifrado binario SPEC-003; al cambiar fuente, dimensión o Pairing se cierra/reinicia el canal y el encoder comienza con IDR. El Servidor nunca captura, decodifica ni genera medios.

## 7. Concurrency

| Concern | Approach |
|---|---|
| Inicio/reutilización del servidor y presentación QR | El host Cemu reutiliza el proceso local o lo inicia; consulta loopback y espera el anuncio Cemu antes de presentar QR |
| Lectura/escritura IPC primary | TCP full-duplex; líneas NDJSON atómicas con LF |
| Captura DRC, inputs y polling | La publicación DRC y el polling SDK no esperan por el socket Android; el snapshot input se protege con lock corto |
| Audio/input sobre Pairing | El Servidor serializa control/input como hoy; video H.264 usa TCP 8425 cifrado independiente, priorizado y ligado al Pairing |
| Encoder DRC | Hilo del adaptador procesa buffers readback; encoder OpenH264 por fuente Cemu, sin B-frames; solo latest pending input y periodicidad configurada |
| Audio local Cemu vs stream Android (CR-012) | El callback TV alimenta solo `g_tvAudio`; el callback DRC alimenta `g_padAudio` y, en paralelo, el IPC PCM estéreo. No hay dependencia del socket de audio para alimentar el callback TV ni conversión de la salida TV a la fuente Android |
| Cambio de fuente | el estado de selección por sesión controla video/audio y el Plugin destino de input |

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
| SPEC-004 touch/orientación y serialización | `core::input`, Kotlin `InputCodec` | TEST-043 |
| SPEC-006 selección por sesión y relay A/V | `GameRegistry`, handler de sesión, escritor de streams | TEST-051 |
| SPEC-004/006 snapshot inicial, retención y polling SDK | sesión PC, IPC primary, `TwinpadGame::poll_input` | TEST-052 |
| SPEC-002 selección Android única/múltiple y liveness | `PairingViewModel`, `PairingActivity` | TEST-053 |
| Cemu host inicia/reutiliza Server, presenta QR y PCM | `TwinpadClient`, renderer Cemu y server relay | TEST-054; build/E2E físico pendiente |
| H.264 OpenH264 → relay binario autenticado → MediaCodec Surface | `TwinpadClient`, `server::media`, Android MediaCodec | TEST-071..074; implementación CR-014 en curso |
| IPC del frame Cemu y encoding Base64URL sin padding | `TwinpadClient::Base64Url` y `FrameLoop` | TEST-065; microbenchmark CPU aislado documentado, debug assertion en build pendiente |
| TV local independiente y PCM DRC/GamePad únicamente hacia Android, sin mezcla/dependencia de transporte | `AIInitDMA` / `AIInitDRCDMA`, `g_tvAudio` / `g_padAudio`, `TwinpadClient::PublishAudioBlock` | TEST-062; audición en banco físico pendiente |
| Ciclo Cemu anterior, configuración y QR loopback | `MainWindow`, `TwinpadClient`, listener QR | TEST-046, TEST-047, TEST-050 |
| VPAD Player 1 temporal cuando falta, restauración de asignación y no persistencia | `InputManager::update_twinpad_vpad`, `InputManager::save` | TEST-064; compilado en Cemu debug, assertions y smoke de título pendientes |

## 13. Related decisions

- CR-004 — integración experimental Cemu; sus límites iniciales de 5 fps fueron reemplazados por CR-010.
- CR-005 — retry 2 s tras inicio y mapeo directo; el fallback de video fue supersedido por CR-010.
- CR-006 — preferencia persistente opt-in, start manual y menú de dos opciones.
- CR-007 — inicio/reutilización del Servidor PC desde Cemu, consulta QR solo loopback y pregunta por detener el proceso propio al cerrar.
- CR-008 — captura interna automática del DRC, sin ventana separada ni selección de pantalla; su máximo histórico de 5 fps fue supersedido por CR-010 (hasta 60 fps configurados).
- CR-009 — Cemu se anuncia antes de presentar QR; cancelación limpia y captura desktop limitada a pruebas explícitas.
- ADR-007 — primera ruta de inputs por sesión LAN autenticada.
- ADR-008 — IPC localhost TCP NDJSON.

## 14. Open questions

- Interoperabilidad de MediaCodec en modelos Android y rendimiento/latencia/energía reales: medir en banco físico (TEST-071..074; NFR-001/005/007). El contrato no promete 1080p60 sin esas mediciones.

## Definition of Done

- [x] Realizes SPEC-004 y SPEC-006 dentro del alcance CR-005.
- [x] Respeta la frontera sesión autenticada → servidor → IPC localhost.
- [x] Retry, desconexión, secuencia y framing descritos.
- [x] Auto-connect persistente opt-in y conexión manual descritos (CR-006).
- [x] Menú Twinpad descrito con “Iniciar conexión” y “Configuraciones”.
- [ ] Probar visualmente ventana de ajustes, persistencia y conexión en vivo en Cemu.
- [x] TEST-043/044, TEST-046/047, TEST-051…054, TEST-064 y TEST-065 registrados en trazabilidad; TEST-064/065 assertions incorporadas al debug unit-test entrypoint, ejecución pendiente.
- [x] Rust tests/Clippy y build Cemu (`Cemu_debug`) verificados; ejecución de assertions TEST-064 y Android unit tests de esta entrega no aplica/no ejecutada.
- [ ] Banco físico Cemu + Android: confirmar hasta 60 fps efectivos y desfase A/V ≤ ±150 ms.


## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-18 | Product owner + Twinpad team | Diseño inicial para CR-005 aprobado |
| 0.2.0 | 2026-10-18 | Product owner + Twinpad team (CR-006) | Preferencia auto-connect opt-in, inicio manual, actualización en vivo y menú Twinpad |
| 0.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-007) | Inicio/reutilización del Servidor PC, QR vigente presentado en Cemu y gestión de ciclo del proceso propio |
| 0.4.0 | 2026-10-18 | Product owner + Twinpad team (CR-008) | Readback DRC interno y automático independiente de la ventana separada; límite 5 fps, sin selección de pantalla |
| 0.5.0 | 2026-10-18 | Product owner + Twinpad team (CR-009) | Anunciar Cemu y confirmar su disponibilidad antes de mostrar QR; cancelar conexión manual y dejar desktop solo para pruebas |
| 0.6.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Host Cemu inicia/reutiliza servidor y presenta QR; DRC hasta 60 fps; relay de fuente seleccionada y polling del snapshot input |
| 0.7.0 | 2026-10-19 | Product owner + Twinpad team (CR-012) | Precisa callbacks: audio TV local PC, PCM DRC/GamePad a Android y salidas independientes sin mezcla ni cambios wire |
| 0.8.0 | 2026-10-19 | Product owner + Twinpad team (CR-013) | VPAD temporal de Jugador 1 si falta, reutilización sin alterar el existente y limpieza sin persistencia |
| 0.8.1 | 2026-10-19 | Twinpad team | Base64URL de IPC Cemu usa salida preasignada; TEST-065 y medición aislada en 720p/1080p, sin cambio de wire |
| 0.9.0 | 2026-10-20 | Product owner + Twinpad team (CR-014) | Añade encoder OpenH264 y canal H.264 TCP autenticado/cifrado; screenshot independiente intacto |
