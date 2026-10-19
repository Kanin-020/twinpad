# Change Request — CR-010 — Relay de plugins y selección de fuente desde Android

| | |
|---|---|
| ID | CR-010 |
| Title | Relay de plugins y selección de fuente desde Android |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (usuario) |

> Aprobación explícita del Product owner: cada integración host que usa un Plugin SDK inicia/reutiliza el Servidor PC y presenta el QR; Android selecciona el Plugin; el Servidor PC solo retransmite/adapta medios publicados por el Plugin y devuelve inputs. El SDK Rust permanece como librería headless; el host (Cemu en la primera integración) posee el ciclo de vida del proceso y la UI del QR.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | CTX, REQ-004/005/006, SPEC-002/003/004/005/006, ARCH, DES-004, traceability |
| Sections | CTX §3/§5/§8; REQ §2/§5/§6; SPEC-002 §4–§16; SPEC-003 §1/§3/§7/§8/§11/§17; SPEC-004 §4/§7/§12; SPEC-005 §3/§4/§7/§12; SPEC-006 §4–§16; ARCH §2–§6; DES-004 §1/§4/§6–§14 |
| Current version → new version | CTX 0.2.0 → 0.3.0; REQ-004/005 0.1.0 → 0.2.0; REQ-006 0.2.0 → 0.3.0; SPEC-002 1.0.0 → 1.1.0; SPEC-003 1.2.0 → 1.3.0; SPEC-004 1.1.0 → 1.2.0; SPEC-005 1.1.0 → 1.2.0; SPEC-006 1.7.0 → 1.8.0; ARCH 0.7.0 → 0.8.0; DES-004 0.5.0 → 0.6.0 |

## 2. Requested change

**Current:**

```text
El Servidor PC captura o elige fallback de video; Cemu publica el DRC mediante readback limitado a 5 fps. La app recibe games_available pero no selecciona un juego en el protocolo de sesión. Los inputs se reenvían al adaptador Cemu por su id fijo; el SDK no expone inputs consumibles.
```

**Proposed — aprobado:**

```text
- La integración host de cada Plugin SDK inicia/reutiliza twinpad-server y presenta el QR. El SDK Rust es headless; Cemu es la primera integración host. Android escanea el QR y, tras Pairing, consume la lista de Plugins vivos: selecciona automáticamente si hay uno; presenta una lista en pantalla si hay varios.
- Android selecciona por sesión con `game_select {type, game_id}`; el Server valida que el Plugin siga registrado y responde `game_selected {type, game_id}`. Si no está disponible, envía la lista `games_available` vigente. Una fuente única se selecciona automáticamente; con varias, Android muestra una lista en pantalla. Sin Plugin seleccionado/vivo no hay video ni audio: no se captura escritorio ni se genera demo.
- El Server no captura ni renderiza video/audio. Recibe los framebuffers y PCM del Plugin, puede codificar/adaptar resolución, tiles y tasa al contrato/configuración acordados por SPEC-003/005, y entrega solo los streams de la fuente seleccionada a Android.
- Cada `input_report` autenticado actualiza el último estado de la sesión. El Server conserva ese snapshot; al seleccionar una fuente lo entrega por IPC y enruta los posteriores solo a esa fuente. El SDK ofrece `poll_input()`, que devuelve el snapshot más reciente y coalesce estados intermedios.
- Cemu publica la textura DRC/GamePad internamente, sin ventana separada ni selector, hasta 60 fps según la configuración del stream, y entrega el PCM DRC existente.
- La validación de esta entrega usa un teléfono/controlador activo. El comportamiento simultáneo de varios teléfonos/gamepads queda fuera de alcance; este CR no define política adicional para un segundo Pairing.
```

## 3. Motivation

El Servidor debe ser independiente de Cemu y de cualquier mecanismo de captura de escritorio: Plugins de otros proyectos publican su Segunda pantalla y audio, y Twinpad solo transporta/adapta esos streams e inputs. Para que el Server sirva varios juegos compatibles, Android debe escoger la fuente por sesión. El tope actual de 5 fps en el adaptador Cemu no satisface el objetivo configurado de video hasta 60 fps.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| CTX | Yes | La fuente audiovisual es el Plugin y el Server retransmite; el QR lo presenta el Plugin host | Actualizar alcance, actores y glosario |
| REQ-004/005 | Yes | Aclarar procedencia de contenido, relay y ausencia de captura Server | Actualizar objetivos, ACs y dependencias |
| REQ-006 | Yes | Plugin publica AV, consume inputs y la app selecciona fuente | Actualizar ACs y flujo de detección |
| SPEC-002 | Yes | UI Android presenta/autoelige Plugins disponibles en sesión | Definir estados/mensajes y lista seleccionable |
| SPEC-003/005 | Yes | Contenido de Plugin, relay/adaptación Server, sin captura/síntesis | Actualizar contrato y fuente |
| SPEC-004 | Yes | Inputs del dispositivo llegan al Plugin seleccionado; snapshot retenido | Actualizar flujo y contrato SDK |
| SPEC-006 | Yes | Mensajes game_select/game_selected, estado por sesión y polling SDK | Definir schema, validación, errores y flujos |
| ARCH | Yes | Server relay, no captura; dirección Plugin→Server→Android | Actualizar responsabilidades y límites |
| DES-004 | Yes | DRC hasta 60 fps; servidor/QR iniciado por Plugin host | Actualizar realización Cemu |
| TEST-051…054 | Yes | Selección, fuente viva, forwarding media/inputs y UI Android | Registrar y ejecutar; E2E Cemu/hardware queda como gate físico |
| Code | Yes | server, core, sdk, app Android y adaptador Cemu | Implementar luego de actualizar contratos |
| Data / migrations | No | Estado de selección e input es por sesión/en memoria | Sin migración |
| Integrations / contracts | Yes | Extiende wire autenticado y API SDK; sin cambio del QR/Handshake | Versionar contrato y mantener validación estricta |

## 5. Options considered

1. Plugin como fuente, selección Android y Server como relay/adaptador — satisface Cemu y futuros Plugins sin acoplar la captura al servidor.
2. Mantener captura/fallback Server — conserva un modo genérico, pero contradice la dirección aprobada y puede solicitar seleccionar monitor.

## 6. Decision

- **Outcome:** `Approved` por el Product owner el 2026-10-18.
- **Rationale:** el Plugin origina audio/video; Android autoelige una fuente única o permite escoger de una lista; el Server adapta/retransmite los medios y reenvía el snapshot más reciente de inputs. Cemu es la primera integración. Límite DRC: hasta 60 fps; SDK consultado por polling. Primera entrega para un solo teléfono/controlador; la concurrencia de gamepads se posterga.
- **Contratos aprobados:** `game_select` y `game_selected` llevan `game_id`; una selección obsoleta produce la lista vigente y conserva una fuente previamente seleccionada si sigue viva; el Servidor retiene el input más reciente antes y después de seleccionar, el SDK consulta con polling y los valores intermedios coalescen.
- **Decisión adicional del Product owner:** el inicio/reutilización del Servidor PC y la presentación del QR son responsabilidad de la integración host de cada Plugin SDK (no de la librería SDK headless); Cemu es el primer host que lo realiza.
- **Aprobación del Product owner:** “Ajuste en el alcance 60 fps de ahi en fuera es correcto”; selector auto si solo hay un juego, lista en pantalla cuando hay varios; el SDK usa polling y el server conserva/entrega el estado inicial del gamepad. Aclaración: cada integración host del Plugin inicia/reutiliza el servidor y presenta el QR; el SDK Rust permanece headless.
- **Rollout:** una versión coordinada de Server, SDK, Android y Cemu. No se declara soporte para varias sesiones Android simultáneas en esta entrega.

## 7. Execution checklist

- [x] Target REQ/SPEC/ARCH/DES actualizados con el contrato aprobado
- [x] `testing/traceability.md` reserva TEST-051…TEST-054
- [x] SDK implementa polling del estado de input más reciente
- [x] Android autoelige un Plugin único y permite elegir desde una lista cuando hay varios
- [x] Server solo emite contenido del Plugin seleccionado; sin captura ni demo
- [x] Cemu publica DRC a tasa configurable hasta 60 fps y conserva PCM DRC (build verificado; tasa efectiva pendiente de banco)
- [x] `cargo fmt --check`, Clippy, tests workspace, Gradle unit tests y build Cemu (`CemuBin`)
- [ ] Validación física Cemu/Android: tasa efectiva hasta 60 fps y desfase A/V dentro de ±150 ms (pendiente del banco)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-18 | Product owner + Twinpad team | Cambio aprobado: Plugin origen de AV, selección Android, relay del Server y polling de inputs |
