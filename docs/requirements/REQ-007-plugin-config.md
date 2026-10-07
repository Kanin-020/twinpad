# REQ-007 — Config externa para juegos no integrados (plugin modelo C)

| | |
|---|---|
| ID | REQ-007 |
| Title | Config externa para juegos no integrados (modelo C) |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Should` |
| Owner | Eng lead |
| Refines | CTX scope item "Integración por plugin" y CTX-C7 |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

No todos los juegos pueden o quieren integrar el SDK Twinpad (REQ-006). La decisión de la entrevista (opción C) permite soportar juegos sin tocar su código: una Config externa describe qué se muestra en la Segunda pantalla y qué controles se activan. Esto extiende el alcance de Twinpad a "cualquier proyecto".

## 2. Description

Twinpad debe poder operar sobre un Juego / Aplicación huesped que NO integra el SDK, a partir de una Config externa provista por la comunidad o el usuario. La Config externa declara, como mínimo, los controles a activar y, cuando el juego lo permite, el origen del contenido de Segunda pantalla. El alcance de lo que puede lograrse sin integración es menor que con SDK (ver notas y limitaciones).

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Juego / Aplicación huesped | Juego sin integración, controlado vía gamepad estándar |
| Config externa | Describe controles (y contenido si es posible) sin tocar el juego |
| Plugin | Rol de la Config externa como modelo de integración C |
| Twinpad Core | Carga la config y la aplica a la sesión |
| Android device | Muestra los controles definidos y captura inputs |

## 4. Preconditions

- Sesión establecida (REQ-002).
- Juego en ejecución (identificado por el servidor, mecanismo definido en SPEC).

## 5. Behavior expected (summary)

- El usuario puede activar una Config externa para un juego sin integración.
- Los controles definidos en la config aparecen en el celular y funcionan vía Gamepad HID.
- La comunidad puede crear configs para juegos nuevos sin modificar Twinpad.

## 6. Acceptance criteria

- [ ] AC-1: Dado un juego sin integración corriendo y una Config externa válida, cuando el usuario la activa, entonces los controles definidos aparecen en el celular y operan vía Gamepad HID.
- [ ] AC-2: Dado un juego sin integración, cuando no existe Config externa para él, entonces el usuario aún puede usar el gamepad genérico (Signature HID por defecto).
- [ ] AC-3: Dado una Config externa inválida o corrupta, cuando se intenta activar, entonces la app rechaza la carga con un mensaje de error específico y no rompe la sesión.

## 7. Non-functional concerns

- NFR-002: la ruta config → inputs no degrada la latencia HID.

## 8. Edge cases and limits

- Config externa que declara más controles que la Signature HID soporta.
- Dos configs disponibles para el mismo juego (elección del usuario).
- Juego detectado incorrectamente (config equivocada aplicada).

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-003 | blocking | Los inputs definidos viajan vía Gamepad HID |
| REQ-008 | soft | Los controles táctiles configurables son la superficie donde la config se aplica |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Limitación conocida: sin SDK, la Segunda pantalla depende de mecanismos de captura del SO; evaluar si v1 la limita a controles — owner: Eng lead — 2026-10-07
- Formato de la config (JSON/DSL) y validación — owner: Eng lead — 2026-10-07

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
