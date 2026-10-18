# Product Context — Twinpad

> ⚠️ TEMPLATE — replace every `<placeholder>` and delete guidance blocks (`> 💡 …`) before moving Status to `Approved`.

> **What this is:** the reason Twinpad exists and the frame inside which every requirement is written. It is the first document a person or AI agent must read.
> **What it is NOT:** a list of features (see `requirements/`) or a description of behavior (see `specs/`).

| | |
|---|---|
| ID | CTX |
| Version | 0.2.0 |
| Status | Draft |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |

## 1. Problem

> 💡 One paragraph, 2–5 sentences. Concrete pain, observable today, with evidence if it exists. No solution talk.

Cuando alguien juega o trabaja en su PC, no tiene un control físico universal para juegos que no lo soportan, y no tiene dónde ver la información secundaria del juego (mapas, inventarios, estadísticas) sin salirse o superponer ventanas sobre la vista principal. Hoy cada aplicación resuelve esto por su cuenta con soluciones cerradas y específicas por juego (por ejemplo, el "GamePad Pro" de CEMU para una sola plataforma), lo que obliga a configurar cada caso por separado. Los jugadores en PC con Android pierden tiempo reconfigurando controles y buscando contenido secundario en pantallas o pestañas distintas.

## 2. Vision / Objective

> 💡 What changes for the user once this system exists. One or two sentences; measurable if possible.

Que cualquier juego o aplicación de PC pueda tener, sin modificar su lógica central, una segunda pantalla en el celular Android y un gamepad completo (físico y táctil) reconocido por el sistema, conectados con una vinculación simple por QR. En visión final, "cualquier app puede integrarse vía plugin" sin trabajo específico por título.

## 3. Scope

> 💡 Capabilities the system WILL provide at capability level (no behavior detail — that belongs in requirements/specs).

- Vinculación PC ↔ celular (pairing mediante QR, descubierto por WiFi LAN / Bluetooth).
- Transmisión de una segunda pantalla desde la PC al celular (framebuffers / imágenes).
- Recepción de inputs del celular en la PC (táctiles, físicos, sensores, vibración inversa).
- Emulación del celular como gamepad HID para que el sistema operativo de la PC lo reconozca como control estándar.
- Integración por plugin para cualquier juego o aplicación (SDK Twinpad + configuración externa).
- Captura y transmisión de audio y video desde la PC al celular (v1).
- Servidor residente en la PC que coordina conexión, streams e inputs.

## 4. Out of scope

> 💡 Explicitly excluded, with the reason. "Out" today can become a REQ tomorrow — this section is what prevents scope creep from being implicit.

| Excluded | Reason |
|---|---|
| Streaming por internet (fuera de la LAN, remote play) | La v1 prioriza estabilidad y latencia en red local; el streaming remoto requiere relays y cifrado adicional |
| iOS / otros sistemas móviles distintos de Android | El stack elegido (Kotlin + Rust, HID nativo) es específico de Android |
| Modo "segunda pantalla" renderizada como HTML/UI genérica | La decisión de v1 es framebuffers e imágenes (decisión de la entrevista); el contenido HTML queda como evolución futura |
| Contenido no interactivo (mirroring completo de escritorio) | Twinpad no es una app de screen-mirroring genérica; la segunda pantalla es para contenido de juegos/apps integradas |
| Soporte general para consolas y emuladores como clientes directos | Se atiende vía integración por Plugin/SDK, no por compatibilidad directa genérica; CR-004 permite únicamente un adaptador experimental puntual de Cemu para prueba semi-real |

## 5. Users and actors

> 💡 Every actor that interacts with the system or is affected by it. Actor names defined here are the vocabulary used by REQs and SPECs — naming matters.

| Actor | Type | Description | Interaction with the system |
|---|---|---|---|
| Jugador / Usuario PC | human | Persona que juega o usa apps en su PC Windows | Es quien experimenta la segunda pantalla y usa el gamepad virtual; configura la sesión y aparea el celular |
| Android device (celular del usuario) | system | Smartphone Android 12+ que corre la app Twinpad | Muestra la segunda pantalla, captura inputs (táctiles, físicos, sensores) y emite HID gamepad |
| PC Host / Servidor Twinpad | system | Computadora Windows/Linux con el servidor Twinpad instalado | Captura framebuffer/audio, recibe inputs, expone QR de vinculación, ejecuta los streams |
| Juego / Aplicación huesped (integrada) | external system | Juego o app que ya integró el SDK Twinpad, o que está descrita por configuración externa | Consume inputs del gamepad virtual y publica contenido de segunda pantalla |
| Plugin Twinpad (integración) | internal service | Módulo (SDK o config externa) que conecta un juego específico con el Core | Declara qué se muestra en la segunda pantalla y qué controles se activan |
| Twinpad Core | internal service | Núcleo común: transporte (LAN/Bluetooth), pairing, HID, streams | Coordina los actores anteriores |

## 6. Constraints

> 💡 Hard constraints imposed from outside: regulation, budget, deadlines, tech mandates, organizational rules. A constraint is non-negotiable; if it becomes negotiable, it moves to Assumptions.

| ID | Constraint | Reason / source |
|---|---|---|
| CTX-C1 | Android mínimo 12 (API 31) | Soporte estable del perfil HID en Bluetooth y APIs maduras (decisión tomada en la entrevista de definición) |
| CTX-C2 | Conectividad limitada a WiFi LAN y Bluetooth | La v1 no contempla streaming por internet; decisión de alcance |
| CTX-C3 | El celular se emula como gamepad HID nativo (no driver virtual de PC) | El PC debe verlo como un control estándar sin instalar drivers adicionales (decisión de la entrevista) |
| CTX-C4 | Stack: Kotlin (lenguaje principal Android) + Rust (crítico/rendimiento) | Decisión de stack de la entrevista; Rust para framebuffers, HID y networking |
| CTX-C5 | Los inputs del celular hacia el juego viajan vía HID: 2 joysticks, 10–12 botones, triggers L/R, gyro X/Y/Z | Descriptor HID definido en la entrevista |
| CTX-C6 | La segunda pantalla transmite framebuffers / imágenes (no HTML) | Decisión de la entrevista; genérico tipo GamePad Pro de CEMU |
| CTX-C7 | Plugins: integración vía SDK Twinpad en el juego (opción B) + configuración externa (opción C) | Decisión de la entrevista; permite soporte sin tocar el código del juego original |

## 7. Assumptions

> 💡 Things taken as true without proof, ranked by how much they hurt if false. An assumption that gets invalidated must trigger re-reading of the affected REQs.

| ID | Assumption | Impact if false | Affects |
|---|---|---|---|
| CTX-A1 | La mayoría de los usuarios de Twinpad están en la misma red LAN o con Bluetooth funcional entre PC y celular | El flujo principal (pairing por QR) no es posible y habría que rediseñar la conectividad v1 | REQ-pairing, REQ-transport |
| CTX-A2 | El celular del usuario soporta Bluetooth HID dispositivo periférico (HID device role) | No se puede emular un gamepad HID nativo; fallback requerido (vía protocolo propio + traductor) | REQ-gamepad-hid |
| CTX-A3 | Los juegos objetivo pueden recibir un input de gamepad estándar por el sistema operativo (XInput/DirectInput) | Los juegos que ignoran HID genérico no funcionan; se limita el alcance del plugin | REQ-plugin-sdk |
| CTX-A4 | El ancho de banda de la LAN soporta flujo de video (framebuffer) a tasa aceptable para jugar | La experiencia de segunda pantalla sería deficiente; se tendría que bajar resolución o framerate | REQ-second-screen, NFR-001 |
| CTX-A5 | Un juego puede correr sin modificación si Twinpad solo le inyecta inputs tipo gamepad estándar | Los juegos que requieren inputs muy específicos (ej. periféricos exclusivos) no serán soportados sin plugin | REQ-plugin-sdk |

## 8. Glossary

> 💡 Canonical, stable definitions. Every domain term used in requirements and specs MUST be defined here. One term = one definition = used identically everywhere.

| Term | Definition |
|---|---|
| Twinpad | Sistema completo: app Android + servidor PC + SDK, que provee segunda pantalla y gamepad para PC vía Android |
| Segunda pantalla | Vista que la PC renderiza, transmite como framebuffers al celular, y el celular muestra en pantalla |
| Gamepad HID | Dispositivo de control estándar que el celular emula vía Bluetooth HID, reconocido por el SO de la PC como control físico |
| Pairing | Proceso de vincular el celular con la PC escaneando el QR que muestra el servidor |
| QR de vinculación | Imagen con la información necesaria para conectar (dirección, protocolo, tokens de sesión) mostrada por el PC |
| Framebuffer | Imagen completa de un frame que la PC captura y transmite al celular |
| Stream | Flujo continuo de datos directo entre PC y celular: video (segunda pantalla), audio o inputs |
| Input físico | Entrada del teléfono: gyro, acelerómetro, botones de hardware, vibración inversa (feedback) |
| Input táctil | Botones y joysticks virtuales en pantalla del celular, configurables |
| Signature HID | Descriptor HID que declara los controles del gamepad (2 joysticks, 10–12 botones, triggers L/R, gyro) |
| SDK Twinpad | Librería que un juego/app integra para publicar contenido de segunda pantalla y consumir inputs (plugin modelo B) |
| Config externa | Archivo (JSON/DSL) que describe segunda pantalla y controles sin modificar el código del juego (plugin modelo C) |
| Plugin | Cualquiera de los dos modelos de integración: SDK Twinpad o config externa |
| Twinpad Core | Núcleo común: transporte, pairing, HID, streams; todo lo demás se apoya en él |
| Servidor PC | Proceso residente en la PC que coordina streams, inputs, y la exposición del QR de vinculación |

## 9. Related documents

- Requirements: `requirements/_index.md`
- Architecture: `architecture/architecture.md`

## Definition of Done

- [ ] Problem describes pain, not a solution.
- [ ] Every scope item is testable at capability level (it can become a REQ).
- [ ] Every actor in section 5 is referenced by at least one requirement, or is explicitly marked as future.
- [ ] Constraints are verifiable and have a source.
- [ ] Every domain term used in any REQ/SPEC exists in the glossary.
- [ ] No placeholders remain; guidance blocks deleted; Status set to `Approved`.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft adapted from template with Twinpad decisions |
| 0.2.0 | 2026-10-17 | Product owner + Twinpad team (CR-004) | Permite adaptador experimental puntual de Cemu; se mantiene excluido el soporte genérico de emuladores |
