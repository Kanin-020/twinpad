# Change Request — CR-044 — Teclado del juego: Android IME en Twinpad en lugar del overlay de Cemu

| | |
|---|---|
| ID | CR-044 |
| Title | Teclado del software (swkbd) del juego: suprimir el overlay de Cemu con Twinpad conectado y escribir desde el teclado de Android |
| Type | `New behavior` |
| Status | `Applied (2026-10-30)` |
| Author | Twinpad team (agente) |
| Created | 2026-10-30 |
| Decided | 2026-10-30 |
| Decided by | Product owner (pedido directo en conversación; decisiones registradas abajo) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 (mensajes `keyboard_request`/`keyboard_input`), DES-004 (ruta swkbd↔teléfono), traceability |
| Sections | SPEC-006 §4 (nuevos mensajes de sesión) y §16; DES-004 §7 (subsección swkbd) |
| Current version → new version | SPEC-006 1.22.0 → 1.23.0; DES-004 1.11.0 → 1.12.0 |

## 2. Requested change

**Current:**

```text
Cuando un juego de Wii U pide entrada de texto, Cemu dibuja su teclado
en pantalla (overlay ImGui de swkbd tanto en la ventana TV como en la
DRC) y se teclea con el mando o el teclado físico del PC, sin relación
con el teléfono. Con Twinpad conectado el teléfono no participa.
```

**Proposed (owner-requested, 2026-10-30):**

```text
1. Si Twinpad está conectado en Cemu (canal IPC primario vivo), el
   overlay ImGui del swkbd NO se dibuja y el teclado físico del PC deja
   de alimentarlo: el único editor es el teléfono.
2. Al aparecer el teclado HLE (SwkbdAppearInputForm / SwkbdAppearKeyboard),
   Cemu emite keyboard_request on=true con el texto vigente y el máximo
   de caracteres; al desaparecer (SwkbdDisappearInputForm/Keyboard) emite
   on=false. Servicio y re-emisión idénticos a camera_request (CR-038).
3. Android, con «cemu» seleccionado y demanda vigente, muestra un campo
   de texto arriba del GamePad y activa el teclado del sistema (IME);
   cada edición envía keyboard_input action=update con el texto completo
   (base64url UTF-8); el botón OK envía action=confirm y Cancelar envía
   action=cancel.
4. Cemu aplica el texto remoto al formString del swkbd (swkbd_inputStringChanged
   preserva la cadena del juego), confirm ⇒ finishInput+desaparecer,
   cancel ⇒ desaparecer sin decidir. Sin Twinpad, el overlay local de
   ImGui funciona exactamente como hoy (sin cambios).
```

## 3. Motivation

El teléfono es el mando; con Twinpad conectado pedirle al PC un teclado
imagen sobre la TV rompe la experiencia (el usuario está en el sofá con
el teléfono). Android ya tiene un IME completo (autocorrección,
diccionarios, teclado propio del sistema): escribir en el teléfono es
más rápido y sin errores, y reutiliza el patrón de mensajes bajo demanda
ya probado (camera_request CR-038, mic_request CR-035).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Yes | Nueva capacidad de input de texto | Reflejado en SPEC-006 |
| SPEC-005 | No | Sin audio | None |
| SPEC-006 | Yes | Mensajes `keyboard_request`/`keyboard_input` y gating | Define (1.23.0) |
| SPEC-003/004 | No | Wire de video/inputs sin cambios | None |
| DES-004 | Yes | Ruta swkbd del adaptador Cemu | Define (1.12.0) |
| TEST | Yes | Codec y relay de los mensajes; test Cemu del formString | TEST-0106..0110 |
| Code | Yes | core (2 codecs), server (cola/requeue/forward), Cemu (swkbd gate + TwinpadClient) y Android (overlay IME) | Large |
| Integrations / contracts | Yes | Dos mensajes nuevos de sesión | Bump coordinado |

## 5. Options considered

1. **Mensajes de sesión tipo demanda (elegida)** — `keyboard_request`
   (Cemu→Android) y `keyboard_input` (Android→Cemu) sobre el canal de
   sesión existente; reutiliza coalescing/requeue probados y no añade
   puertos ni medios nuevos.
2. Emular el teclado táctil vía `input_report` touches — el overlay ImGui
   sigue dibujado y el teléfono «toca» sus teclas: frágil (layout dependiente
   de la resolución/píxel) y con la misma latencia por frame.
3. No hacer nada — el usuario debe teclear sentado frente al PC.

**Decisiones del usuario (2026-10-30):**

- Con Twinpad conectado el overlay de Cemu se suprime por completo
  (también la ruta del teclado físico del PC hacia swkbd).
- El teclado del sistema (IME) de Android es el editor del teléfono.
- Sin toggle opt-in en el drawer: el teclado aparece porque el juego lo
  pidió (a demanda pura, como el micrófono CR-035).
- El texto viaja como mensaje completo por edición (no por tecla simple)
  en base64url UTF-8; OK/Cancelar son acciones explícitas.

## 6. Decision

- **Outcome:** Approved (owner-requested). Implementación completa de una
  vez: core (codecs), server (relay), Cemu (supresión + aplicar texto) y
  Android (IME). **Implementada y aplicada el 2026-10-30.**
- **Nota de implementación (cancel), actualizada 2026-10-30:** `keyboard_input
  action=cancel` cierra el form del HLE `swkbd` SIN marcar
  `decideButtonWasPressed` (`swkbd_cancelInput` en swkbd.cpp): el juego
  percibe el cierre con su polling de estado (igual que tras
  `SwkbdDisappearInputForm`) y el teléfono recibe `on=false`; el texto
  queda sin validar (retroceder). Esta nota reemplaza la limitación
  original (cancel ≡ confirm); puntuada abajo en el changelog 1.1.0.
- **Nota de física pendiente:** la verificación con un juego real
  (SwkbdAppearInputForm con texto inicial, IME visible, update/confirm
  visibles en el formString del juego) queda para el banco físico, como
  en CR-034.
- **Rationale:** reutiliza íntegramente el patrón probado de
  `camera_request`/`mic_request` y el canal IPC/she session ya existente.
- **Rollout:** build coordinado; la compatibilidad con juegos concretos
  (formatos de swkbd exóticos) se verifica en banco físico.

## 7. Execution checklist

- [x] Product owner approval recorded
- [x] Target document(s) updated (SPEC-006 1.23.0, DES-004 1.12.0, traceability)
- [x] Tests added/updated: TEST-0106 (core codecs), TEST-0107/0108 (server relay/cola),
  TEST-0109 (Android wire), TEST-0110 (Cemu demanda/cola estricta); verificación
  física del formString queda para el banco (como CR-034)
- [x] `testing/traceability.md` rows updated
- [x] `_index.md` updated
- [x] Code change applied: core/src/game.rs; server/src/games.rs + main.rs;
  Cemu: TwinpadClient.h/.cpp (Publish/FeedKeyboardInput + replay en ConnectPrimary),
  swkbd.cpp (supresión del overlay con `IsConnected`, publicación en
  appear/disappear/keyInput, aplicación de pendientes en el hilo de render);
  Android: KeyboardCodec.kt, KeyboardOverlayController.kt, activity_pairing.xml,
  PairingViewModel.kt, PairingActivity.kt
- [x] Gates verdes (2026-10-30): cargo fmt/clippy -D warnings + 88 core + 52 server
  (TEST-0106/0107/0108), detekt/ktlint/tests/assembleDebug Android (TEST-0109),
  build Cemu release completo (TEST-0110 compilado en UnitTests)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-30 | Twinpad team (agente) | Opened and Approved (owner-requested) |
| 1.0.0 | 2026-10-30 | Twinpad team (agente) | Applied: implementación completa 4 capas, gates verdes, cancel tratado como confirm por limitación del HLE (nota §6) |
| 1.1.0 | 2026-10-30 | Twinpad team (agente) | Física verificada (escritura Bulldogs y ecos OK); arregla el eco del form: la publicación leía el buffer `wchar_t` de 32-bit como unidades UTF-16 y desfasaba el texto tras la primera letra (NULs en el EditText); `cancel` ya no ≡ `confirm` — cierra el form sin decidir (`swkbd_cancelInput`, §6 actualizado) con validación física pendiente del banco; `update` con texto vacío permitido (borrar todo); botón «Cancelar» a la izquierda y «Aceptar» a la derecha en el overlay de Android |
