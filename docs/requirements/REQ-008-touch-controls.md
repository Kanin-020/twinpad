# REQ-008 — Controles táctiles configurables en la app

| | |
|---|---|
| ID | REQ-008 |
| Title | Controles táctiles configurables en la app |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Should` |
| Owner | Eng lead |
| Refines | CTX scope item "Recepción de inputs del celular en la PC (táctiles...)" |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

Los Input táctil (joysticks virtuales y botones en pantalla) son la mitad de la propuesta de valor del Gamepad HID: sin ellos, el celular solo aporta sensores. La entrevista los define como configurables, para que cada juego o usuario adapte la superficie de control sin recompilar nada (y para que las Configs externas de REQ-007 tengan dónde aplicarse).

## 2. Description

La app Twinpad en el Android device debe permitir al usuario (o a un Plugin vía REQ-006/REQ-007) definir la superficie de control táctil: qué controles aparecen, dónde y con qué asignación hacia la Signature HID. La configuración debe poder guardarse, copiarse y aplicarse por juego.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Android device | Muestra y captura los controles táctiles configurados |
| Jugador / Usuario PC | Configura la superficie de control a su gusto |
| Plugin | Puede proveer la configuración (SDK o config externa) |
| PC Host | Recibe los inputs mapeados a la Signature HID |

## 4. Preconditions

- App instalada y sesión activa (REQ-002) para usarlos; la edición de la configuración puede hacerse sin sesión.

## 5. Behavior expected (summary)

- El usuario puede agregar, mover, redimensionar y asignar controles táctiles.
- La configuración puede guardarse por juego y reutilizarse.
- Los controles configurados envían sus inputs por la Signature HID (REQ-003).

## 6. Acceptance criteria

- [ ] AC-1: Dado la app en modo edición, cuando el usuario agrega un botón y lo asigna a un elemento de la Signature HID, entonces el botón queda operativo en la próxima sesión.
- [ ] AC-2: Dado una configuración guardada para un juego, cuando ese juego se detecta en una nueva sesión, entonces la app carga automáticamente esa configuración.
- [ ] AC-3: Dado una configuración con un control sin asignar, cuando el usuario la activa, entonces la app advierte el conflicto y el control sin asignar no envía inputs.

## 7. Non-functional concerns

- NFR-002: la edición no debe introducir latencia en la ruta de inputs.
- NFR-007: evitar consumo excesivo por renderizado de la superficie de controles.

## 8. Edge cases and limits

- Configuración con más controles que la Signature HID soporta.
- Import de configuración de otro dispositivo o de la comunidad.

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-003 | blocking | Destination de los inputs táctiles |
| REQ-007 | soft | Las Configs externas se apoyan en esta superficie |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Definir si la edición es solo en el celular o también desde la PC — owner: Eng lead — 2026-10-07

## Definition of Done

- [ ] Every acceptance criterion is objective and binary-pass.
- [ ] Actors exist in `product/context.md` §5.
- [ ] Terms used are defined in the glossary.
- [ ] At least one SPEC implements this REQ (when Status is `Implemented`).
- [ ] No implementation details (tech, frameworks, endpoints, schemas).
- [ ] Traceability row exists in `testing/traceability.md`.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft |
