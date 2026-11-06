# Change Request (CR) — CR-090 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-090 |
| Title | Menú «Enviar micrófono al GamePad» en el drawer (justo encima del de cámara) y retirada de la mención a la integración en el título de cámara |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Owner («Añade en android el menú de mandar microfono así como el de camara, ponlo justo arriba del de camara y quita todo el texto relacionado con CEMU en android») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-006` (§4, fila `mic_chunk`) |
| Sections | §4 |
| Current version → new version | `1.25.0 → 1.26.0` |

## 2. Requested change

**Current:**

```text
El drawer no tiene sección de micrófono: la captura de CR-035 la gobiernan
solo el juego seleccionado y `mic_request on=true` del juego (el usuario no
puede silenciarla desde la app).
El título de la sección de cámara es «Cámara del teléfono (Cemu)».
```

**Proposed:**

```text
- Nueva sección «Micrófono del teléfono» en el drawer, justo ENCIMA de la de
  cámara y con su mismo patrón: título + interruptor «Enviar micrófono al
  GamePad» (preferencia local persistente, clave `microphone_stream`).
- Activado por defecto: CR-035 ya gobierna la captura por demanda (juego
  seleccionado + `mic_request`), así que un opt-in apagado habría dejado sin
  micrófono a los juegos que ya lo usan; desmarcado para la captura y no se
  pide permiso (`RECORD_AUDIO` solo se solicita con el interruptor activo y
  demanda vigente).
- El gate de `mic_chunk` pasa a: juego seleccionado **Y** `mic_request
  on=true` **Y** interruptor activado. El interruptor aplica en caliente
  (no espera a un nuevo `mic_request`); wire sin cambios.
- El título de cámara queda «Cámara del teléfono», sin la mención a la
  integración, y el comentario del layout deja de nombrarla.
```

## 3. Motivation

Pedido del owner: quería el control del micrófono en la app con la misma forma que el de cámara (que ya tiene interruptor desde CR-034) y pidió limpiar el texto de la integración en Android. La captura de micrófono existía desde CR-035 pero sin control visible: se activaba sola con la demanda del juego y el usuario no podía silenciarla.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | no | La capacidad de micrófono ya existe (CR-035); se añade un opt-out local | none |
| SPEC-002 | no | El drawer documenta resolución/bitrate/HUD/controles/distribución; el toggle de cámara de CR-034 tampoco figura aquí | none |
| SPEC-006 | yes | §4 fila `mic_chunk`: tercer gate (interruptor del drawer), activado por defecto | update (1.26.0) |
| ARCH | no | Sin componentes ni fronteras nuevas | none |
| DES-002 | no | El drawer ya es el dueño de las preferencias locales (`AndroidSettingsStore`) | none |
| TEST-069 | yes | Cubre las preferencias locales; se añaden las filas del micrófono (default activo y wire intacto) | update |
| Code | yes | `strings.xml`, `drawer_settings.xml`, `AndroidSettingsStore.kt`, `SettingsDrawerController.kt`, `PairingActivity.kt` | aplicado |
| Data / migrations | no | Preferencia nueva con default activo; sin migración (una instalación previa lee el default) | none |
| Integrations / contracts | no | `mic_chunk`/`mic_request` sin cambios de formato | none |

## 5. Options considered

1. Interruptor con default **activado** (elegido) — no altera la regla vigente de CR-035; el usuario puede silenciar y el permiso de micrófono solo se pide con la demanda real del juego.
2. Interruptor con default apagado (espejo estricto del opt-in de cámara de CR-034) — habría desactivado el micrófono de juegos que ya lo usaban hasta que el usuario lo descubriera.
3. Do nothing — el micrófono sigue sin control visible en la app.

## 6. Decision

- **Outcome:** `Approved` (instrucción directa del owner) y aplicado.
- **Rationale:** el menú replica el de cámara (título + interruptor) y se coloca encima por pedido explícito; el default activado mantiene el comportamiento documentado de CR-035, y desmarcarlo silencia la captura sin tocar el wire.
- **Rollout:** misma iteración, solo app Android; la captura real se valida en banco físico con un juego que abra el micrófono del GamePad.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-006 1.26.0 con changelog de este CR)
- [x] Dependent documents updated (ninguno)
- [x] Tests added/updated (TEST-069 extendido; sin TEST nuevo: el gate vive en la Activity y la preferencia se cubre donde ya se prueba)
- [x] `testing/traceability.md` rows updated (historia de cambios)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto y aplicado: sección de micrófono con interruptor sobre la de cámara, gate adicional en `mic_chunk`, título de cámara sin la mención a la integración |
