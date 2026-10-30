# Change Request — CR-034 — Cámara del teléfono hacia Cemu

| | |
|---|---|
| ID | CR-034 |
| Title | Enviar video de la cámara del teléfono a Cemu como entrada de cámara del VPAD |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-28 |
| Decided | 2026-10-30 |
| Decided by | Product owner (conversación; elecciones registradas abajo) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 (mensaje `camera_frame`), DES-004, traceability |
| Sections | SPEC-006 §4 (entradas) y §16 (verificación); DES-004 ruta de cámara del VPAD |
| Current version → new version | Propuesto; pendiente de versión |

## 2. Requested change

**Current:**

```text
Android uses the camera only to scan the pairing QR. Cemu receives no camera image from the phone, and the VPAD camera path is not implemented.
```

**Proposed:**

```text
Lente seleccionada del teléfono (frontal por defecto, selector frontal/trasera
en el drawer) → H.264 (320x240, 30 fps) por el canal del Servidor → Cemu.
Cemu decodifica H.264 y rellena la superficie de CAM del VPAD en NV12 (YUV 4:2:0).
Decisiones del usuario: transporte H.264 por el canal del plugin; 320x240 a 30 fps;
exportaciones de cámara del VPAD dentro de este CR; envío opt-in (toggle + selector
del drawer, CR-034); gates de sesión sin cambio (cemu seleccionado Y demanda vigente,
CR-038).
```

## 3. Motivation

Games that use the Wii U GamePad camera cannot receive an image from the phone. Evidence: Android camera code exists only for QR scanning; no camera message exists in the Cemu plugin protocol.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Maybe | New capability | Confirm scope |
| SPEC-005 | Yes | New camera stream direction and format | Define |
| SPEC-006 | Yes | New camera message | Define |
| DES-004 | Yes | Cemu VPAD camera path | Define |
| TEST | Yes | Frame encode/decode and stale-frame tests | New TEST IDs |
| Code | Yes | Android capture, plugin message, Cemu VPAD camera exports | Large |
| Integrations / contracts | Yes | New message and camera permission | Version bump |

## 5. Options considered

1. Stream phone camera frames as H.264 over the plugin channel, reusing the video encoder path — consistent with CR-014/CR-020.
2. Do nothing — games that use the GamePad camera get no image.

**Open questions for the approver:**
- Decidido por el usuario: la cámara enviada a Cemu es la **frontal**.
- Decidido por el usuario: 320x240 a 30 fps; transporte H.264 por el canal del plugin.
- Decidido por el usuario: las exportaciones de cámara del VPAD se incluyen en este CR.
- Decidido por el usuario: formato de superficie CAM en NV12 (YUV 4:2:0).
- Decidido por el usuario (2026-10-30): implementación completa de una vez
  (Android + Servidor + Cemu) y envío opt-in desde el drawer de Android con
  toggle `Enviar cámara al GamePad` Y selector de lente frontal/trasera
  (frontal por defecto; preferencia persistente).
- Pendiente de confirmar en hardware: el formato NV12 que espera el juego real.

## 6. Decision

- **Outcome:** Applied. Android captures the selected lens (front by default,
  front/back selector in the drawer) at 320x240/30 fps, encodes H.264 and sends
  `camera_frame` through the Servidor to Cemu; Cemu decodes it and fills the CAM
  target surface as NV12 (`TwinpadCameraDecoder` → HLE `camera`).
- **Rationale:** reuses the existing H.264 path and the plugin channel; no new
  transport or port. El toggle respeta el principio opt-in; sin frames el juego
  conserva la última imagen de la superficie CAM.
- **Rollout:** camera stream is opt-in from the Android menu (toggle + lens
  selector, preferencia persistente); todavía sujeto además a los gates de
  sesión existentes (juego «cemu» + demanda `camera_request`).

## 7. Execution checklist

- [x] Scope defined and approved (2026-10-30: completo de una vez; toggle + selector)
- [x] Target document(s) updated (SPEC-006 1.22.0; DES-004 1.11.0)
- [x] Tests added/updated (TEST-093/094/095/096 ya definidos; wire sin cambios)
- [x] `testing/traceability.md` rows updated
- [x] `_index.md` updated
- [x] Code change applied (Android: `CameraStreamEncoder`/`PairingCameraController`
  con lente, toggle + selector en el drawer persistidos; gate combinado en
  `PairingActivity`; Servidor: relay existente; Cemu: `TwinpadCameraDecoder` +
  HLE `camera` con NV12)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Twinpad team (agente) | Opened, replacing the screen-toggle draft |
| 1.0.0 | 2026-10-30 | Twinpad team (agente) | Applied: implementación completa (Android/Servidor/Cemu) con opt-in toggle + selector frontal/trasera; SPEC-006 1.22.0 y DES-004 1.11.0; gates y verificación física del formato NV12 quedan al banco |
| 0.2.0 | 2026-10-28 | Twinpad team (usuario + agente) | Approved: H.264 por el canal del plugin, 320x240 a 30 fps, cámara frontal, superficie NV12; corrige el destino a SPEC-006 |
