# Architecture — Twinpad

> ⚠️ TEMPLATE — replace every `<placeholder>`, delete guidance blocks (`> 💡 …`) before `Approved`.

> **What this is:** the system-level structure: components, responsibilities, boundaries, and the technical decisions with system-wide reach.
> **What it is NOT:** detailed design of a SPEC (see `design/`) or why a specific choice was made (see `decisions/` — reference ADRs here).

| | |
|---|---|
| ID | ARCH |
| Version | 1.0.0 |
| Status | Draft |
| Owner | Twinpad team |
| Last updated | 2026-10-20 |

## 1. System context

> 💡 The system as a black box: what surrounds it. Users, external systems, protocols. One diagram (C4 level 1) + short prose. Use actor names from `product/context.md` §5.

Twinpad conecta el **PC Host** (Windows, con juegos/aplicaciones) con el **Android device** (celular del usuario) sobre **WiFi LAN** y/o **Bluetooth**. El **Jugador** aparea ambos con un QR. Los juegos integran Twinpad vía **Plugin** (SDK o Config externa). El sistema NO opera fuera de la red local (v1).

```mermaid
C4Context
  title Twinpad — System context (v1)
  Person(jugador, "Jugador / Usuario PC", "Usa la Segunda pantalla y el Gamepad HID")
  System(twinpad, "Twinpad", "Segunda pantalla + gamepad para PC desde Android")
  System_Ext(juego, "Juego / Aplicación huesped", "Consume inputs; origina contenido de segunda pantalla")
  SystemDb(pchost, "PC Host (Windows)", "Ejecuta el Servidor Twinpad")
  SystemDb(android, "Android device (12+)", "Ejecuta la app Twinpad")

  Rel(jugador, twinpad, "Configura, aparea y juega")
  Rel(twinpad, juego, "Inyecta inputs vía Gamepad HID; recibe contenido vía Plugin")
  Rel(pchost, twinpad, "Hosts Servidor PC + Twinpad Core")
  Rel(android, twinpad, "Hosts app Twinpad (cliente)")
```

## 2. Components and responsibilities

> 💡 One row per component with a single crisp responsibility. If you cannot state the responsibility in one sentence, the decomposition is wrong.

| Component | Responsibility | Type | Specs served |
|---|---|---|---|
| `Servidor PC` | Gestionar sesiones y retransmitir/adaptar medios e inputs entre Android y el Plugin seleccionado | service (persistente) | REQ-001, REQ-002, REQ-004, REQ-005, REQ-006 |
| `Twinpad Core (PC)` | Ejecutar transporte LAN/Bluetooth y coordinar el relay autenticado de medios publicados por Plugins e inputs del Android | module (Rust) | REQ-003, REQ-004, REQ-005 |
| `SDK Twinpad` | Permitir al Plugin anunciarse, publicar medios y consultar por polling el último snapshot de input | lib | REQ-006 |
| `Integración host del Plugin` | Iniciar/reutilizar el Servidor PC y presentar el QR para Pairing | responsabilidad del host/integración | CR-010, REQ-006 |
| `Adaptador experimental Cemu` | Codificar readback DRC a H.264 con encoder hardware opt-in y fallback OpenH264 CPU, publicar A/V e inputs del GamePad emulado | module (C++) | CR-004..CR-010/CR-012/CR-014/CR-020, SPEC-003/004/006 |
| `Cargador de Config externa` | Cargar y validar configuraciones de juegos no integrados | module (Rust) | REQ-007 |
| `App Twinpad (Android)` | Mostrar la Segunda pantalla mediante MediaCodec/Surface y capturar inputs táctiles/físicos | app (Kotlin) | REQ-004, REQ-008, CR-014 |
| `Motor HID (Android)` | Presentar el celular como Gamepad HID vía Bluetooth y codificar inputs al descriptor | module (Kotlin+Rust) | REQ-003 |
| `Relay de medios (PC)` | Retransmitir opacamente AUs H.264 cifradas del Plugin seleccionado y PCM | module (Rust) | REQ-004, REQ-005, REQ-006, CR-014 |

## 3. Boundaries and dependencies

> 💡 What may depend on what. Direction rules prevent cycles. State allowed and forbidden dependencies explicitly — this is what agents must respect.

| From | To | Allowed via | Forbidden |
|---|---|---|---|
| `App Twinpad (Android)` | `Twinpad Core (PC)` | Protocolo de sesión (transporte) | Acceso directo al IPC local del Plugin o al proceso host |
| `Motor HID (Android)` | `App Twinpad (Android)` | Interfaz interna de inputs | Conocer el juego o su contenido |
| `Integración host del Plugin` | `Servidor PC` | iniciar/reutilizar proceso y presentar QR de Pairing | el SDK headless inicia procesos o presenta UI |
| `SDK Twinpad` | `Servidor PC` | IPC local de anuncio/publicación y consumo de inputs | escribir directamente a la sesión LAN Android |

| `Adaptador experimental Cemu` | `Servidor PC` | Inicio del proceso, consulta QR loopback e IPC de plugin conforme a SPEC-006; publicación DRC hasta 60 fps configurados | Acceso directo a la sesión Android; captura por Servidor PC |
| `Cargador de Config externa` | `Twinpad Core (PC)` | Modelo de configuración validado | Ejecutar código del juego |
| `Twinpad Core (PC)` | `Servidor PC` | Eventos internos de sesión | Depender del juego concreto |
| `Servidor PC` | `Android device` | control NDJSON por Pairing + canal media TCP 8425 autenticado/cifrado (SPEC-003 §4) | Publicar medios no originados por el Plugin seleccionado; no decodificar ni transcodificar H.264 |

Regla transversal: ningún componente del lado PC importa código de Android y viceversa; la única frontera es el protocolo de sesión (ver §4).

## 4. Communication

> 💡 How components talk: sync (RPC), async (streams), plus failure semantics (timeouts, retries, idempotency, ordering guarantees). Contract formats are referenced, not duplicated (see SPEC §12 style).

| Interaction | Style | Contract | Failure semantics |
|---|---|---|---|
| `Android → Servidor PC` (pairing) | sync handshake | Payload del QR + challenge (SPEC del pairing) | QR expirado → rechazo; reintentos con backoff |
| `Plugin → Servidor → Android` (Segunda pantalla) | control NDJSON + stream TCP binario separado | H.264 AVC del Plugin seleccionado (SPEC-003 v1.5.0); encoder hardware opt-in con fallback OpenH264 en Cemu, relay opaco, MediaCodec Android | Handshake HMAC derivado de sesión; AES-256-GCM; sin selección/fuente viva → sin video |
| `Plugin → Servidor → Android` (audio) | stream continuo | PCM del Plugin seleccionado (SPEC-005) | Sin fuente PCM → silencio; sin captura/síntesis Server |
| `Android → Servidor → Plugin` (inputs) | snapshots de sesión | Signature HID (SPEC-004), selección SPEC-006, SDK polling | Secuencia antigua descartada; último snapshot retenido por sesión |
| `PC → Android` (vibración) | evento | Contrato de rumble (SPEC HID) | Fire-and-forget con mejor esfuerzo |
| `Cemu TV → PC` / `Cemu DRC → Android` | callbacks de salida separados | CR-012, SPEC-005 §8.4 / SPEC-006 R11 | TV mantiene salida local; el único PCM Android es DRC/GamePad; las rutas no se mezclan y el wire no cambia |
| `Plugin → Servidor PC` (SDK) | IPC local bidireccional | Anuncio/audio/input conforme a SPEC-006; AUs H.264 según CR-014 | Plugin cae → streams de esa fuente terminan sin romper Pairing |
| `Host de Plugin SDK → Servidor PC` (inicio/QR) | start/reuse + consulta local síncrona | host gestiona proceso y presentación; QR loopback de Cemu en DES-004 | servidor/QR inaccesible → host informa error y no inicia Pairing |
| `Servidor PC → Android` (lista/selección) | mensajes de sesión | SPEC-006 `games_available`, `game_select`, `game_selected` | selección obsoleta devuelve lista vigente; conserva selección viva previa |

## 5. Persistence

> 💡 Which component owns which data. Ownership (single writer) is an architectural rule; detailed schemas live in `design/` or the data store itself.

| Data | Owner | Store | Notes |
|---|---|---|---|
| Perfiles de controles táctiles | `App Twinpad (Android)` | almacenamiento local Android | PII: ninguna; exportables |
| Configs externas de juegos | `Cargador de Config externa` | archivos locales en PC | Comunidad/usuario |
| Preferencia de inicio automático Cemu | `Adaptador experimental Cemu` | configuración persistente local de Cemu | `false` por defecto; implementación del primer host SDK, no política del SDK headless |
| Preferencia de aceleración hardware H.264 Cemu | `Adaptador experimental Cemu` | configuración persistente local de Cemu | `twinpad_hardware_acceleration`, `false` por defecto; fallback OpenH264 CPU (CR-020) |
| Tokens/sesiones de pairing | `Servidor PC` | almacén local cifrado + sesión activa en memoria | `session_key` disponible solo en RAM mientras vive Pairing; revocable |

## 6. Integrations

> 💡 Every external system, with the failure story. If an integration has no failure semantics, that is an open question.

| External system | Purpose | Direction | Contract | Failure behavior |
|---|---|---|---|---|
| Integración host del Plugin SDK | Iniciar/reutilizar Servidor PC y presentar QR | inbound | contrato host de REQ-006/SPEC-006 | Error del servidor/QR → host informa y no presenta Pairing |
| Juego / Aplicación huesped (con SDK) | Publicar A/V y consumir inputs | bidirectional vía Plugin/Servidor PC | API SDK headless (SPEC-006) | Sin Plugin seleccionado no hay A/V; fallo Plugin detiene sus streams sin romper Pairing |
| Cemu (adaptador experimental CR-004..CR-010) | Inicia/reutiliza Servidor, presenta QR, publica DRC/PCM y consume snapshots de input | bidirectional vía Servidor PC | QR local y SDK/IPC de SPEC-006; DRC hasta 60 fps configurados | Fallo local → Cemu informa; el servidor no captura escritorio ni genera demo |
| Juego sin integración (vía Config externa) | Solo inputs (gamepad estándar) | inbound | Signature HID | Sin captura de pantalla garantizada |
| SO Windows (HID) | Registrar el Gamepad HID | outbound | Driver/HID del sistema | Sin registro → juego no ve el control |
| Router WiFi LAN / Bluetooth | Transporte | — | CTX-C2 | Degradación por señal; reconexión automática |

## 7. Security

> 💡 System-wide security posture: authN/authZ model, secrets handling, encryption. Requirement-specific rules live in REQs/SPECs.

| Aspect | Decision | ADR |
|---|---|---|
| authN | Solo dispositivos con token del QR (NFR-004) | ADR-004 |
| Cifrado del transporte | Pairing según SPEC-001; media TCP 8425 autenticado HMAC y cifrado AES-256-GCM con subclave HKDF por conexión (CR-014/SPEC-003) | CR-014 |
| Secrets | Token/`session_key` solo en memoria/almacén protegido; no se entrega a Cemu ni se registra; media keys efímeras por conexión | CR-014 |
| Superficie de red | Pairing y TCP media 8425 accesibles en LAN con autenticación; consulta QR Cemu solo `127.0.0.1:8423`; SDK IPC solo localhost | CTX-C2, CR-007, CR-014 |

## 8. Scalability and performance

> 💡 Horizontal/vertical strategy, stateless vs stateful components, scaling limits, bottlenecks. Numbers come from NFRs (reference by ID, do not restate).

| Aspect | Approach | Constraint (NFR) |
|---|---|---|
| Sesiones simultáneas | Multi-sesión soportada (NFR-006) | NFR-006 |
| Latencia de streams | Prioridad de envío: inputs > video > audio | NFR-001, NFR-002 |
| CPU en Android | MediaCodec AVC por hardware + SurfaceView; validar capacidad del dispositivo y fallback no especificado | NFR-005, CR-014 |
| Batería | Batch de inputs; decoder por hardware | NFR-007 |

## 9. Observability

> 💡 Logs, metrics, traces, health checks, alerting philosophy. Concrete log formats belong to engineering guidelines; what to monitor belongs here.

| Aspect | Approach |
|---|---|
| Logging | Eventos de sesión (pairing, desconexión) con ID de sesión; sin tokens ni contenido de pantalla |
| Metrics | Latencia de stream (NFR-001), input lag (NFR-002), fps entregados, pérdida de paquetes |
| Tracing | Correlación por session-id en ambos extremos |
| Health | Indicador visible de estado del Servidor PC (REQ-001 AC-2) |

## 10. Deployment (when relevant)

> 💡 Environments, artifacts, configuration, migration strategy.

| Aspect | Decision |
|---|---|
| Environments | Local del usuario (PC + celular); no hay servidor central |
| Artifact / packaging | Instalador de PC (servidor persistente, REQ-001) + APK Android 12+ |
| Config | Archivos locales; perfiles por juego |
| Migrations | Versionado de Configs externas y del protocolo de sesión |

## 11. Open questions

- Mecanismo exacto de autoarranque del Servidor PC (servicio vs startup) — Eng lead — 2026-10-07 — abierta
- Estrategia de detección de juegos sin SDK (proceso activo vs ventana) — Eng lead — 2026-10-07 — abierta

## Definition of Done

- [ ] Every component has a single-sentence responsibility.
- [ ] Dependency rules are explicit (allowed AND forbidden).
- [ ] Every integration has failure semantics.
- [ ] Data ownership is explicit (single writer per data set).
- [ ] Cross-cutting decisions reference ADRs by ID instead of narrating rationale.
- [ ] No detail that belongs to a DES (schemas, function signatures, class diagrams).
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft from interview decisions |
| 0.2.0 | 2026-10-17 | Twinpad team (CR-004) | Registra adaptador Cemu experimental, separado de SDK oficial |
| 0.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-005) | Cemu experimental bidireccional por título; reintento/captura DRC automáticos e inputs directos al VPAD emulado |
| 0.4.0 | 2026-10-18 | Product owner + Twinpad team (CR-006) | Control manual y preferencia opt-in persistente para iniciar la conexión Cemu |
| 0.5.0 | 2026-10-18 | Product owner + Twinpad team (CR-007) | Cemu inicia/reutiliza twinpad-server desde PATH y solicita QR vigente por endpoint limitado a loopback |
| 0.6.0 | 2026-10-18 | Product owner + Twinpad team (CR-008) | Cemu captura internamente el DRC a 5 fps sin activar la vista separada |
| 0.7.0 | 2026-10-18 | Product owner + Twinpad team (CR-009) | Registro Cemu confirmado antes del QR; captura desktop queda para pruebas explícitas |
| 0.8.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Host de cada Plugin inicia/reutiliza server y presenta QR; relay de medios, selección Android y polling input sin captura/generación A/V en Server |
| 0.9.0 | 2026-10-19 | Product owner + Twinpad team (CR-012) | Cemu conserva TV en PC y envía DRC/GamePad a Android en rutas independientes |
| 1.0.0 | 2026-10-20 | Product owner + Twinpad team (CR-014) | Migra el video a H.264 OpenH264; relay PC opaco por TCP media autenticado/cifrado y MediaCodec Android |
| 1.1.0 | 2026-10-23 | Product owner (CR-020) | Encoder H.264 hardware opt-in para Cemu, fallback OpenH264 CPU y contrato/wire H.264 invariantes |
