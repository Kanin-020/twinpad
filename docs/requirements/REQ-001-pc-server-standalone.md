# REQ-001 — Servidor PC persistente y empaquetado

| | |
|---|---|
| ID | REQ-001 |
| Title | Servidor en PC persistente y empaquetado |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Servidor residente en la PC" |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

El problema (CTX §1) incluye "configurar cada caso por separado": si el servidor de Twinpad fuera una app que hay que abrir manualmente cada vez, cada sesión volvería a ser una configuración manual. El usuario necesita que el Servidor Twinpad esté siempre disponible en su PC, instalado una sola vez.

## 2. Description

Twinpad debe proveer un Servidor PC que se instale y ejecute de forma persistente en la PC del Jugador, sin requerir intervención manual en cada arranque, y que esté disponible para aceptar sesiones de Pairing en cualquier momento. El mecanismo exacto de arranque e instalación se define en los SPECs que implementen este REQ.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Jugador / Usuario PC | Instala el servidor una vez y espera que esté siempre activo |
| PC Host | Ejecuta el Servidor Twinpad de forma persistente |

## 4. Preconditions

- PC Host encendida y con el Servidor Twinpad instalado.

## 5. Behavior expected (summary)

- El Servidor Twinpad se encuentra activo sin que el usuario lo abra manualmente en cada sesión.
- El usuario puede saber en todo momento si el servidor está activo.
- El servidor puede instalarse/desinstalarse como una aplicación común de PC.

## 6. Acceptance criteria

- [ ] AC-1: Dado un PC con el Servidor Twinpad instalado, cuando el usuario reinicia la máquina e inicia sesión, entonces el servidor está activo sin que el usuario lo haya abierto manualmente.
- [ ] AC-2: Dado el servidor activo, cuando el usuario consulta su estado (ícono/indicador), entonces puede distinguir claramente entre activo e inactivo.
- [ ] AC-3: Dado un PC sin el servidor activo, cuando un celular intenta vincularse, entonces la vinculación falla con un mensaje de "servidor no disponible" en el celular.

## 7. Non-functional concerns

- NFR-003: operación persistente y de bajo consumo en idle.

## 8. Edge cases and limits

- Comportamiento tras suspensión/hibernación del PC.
- Comportamiento cuando el usuario cierra sesión de Windows.

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| CTX-C2 (WiFi LAN / Bluetooth) | soft | El servidor escucha en esos medios |
| REQ-002 | soft | El pairing es el consumidor principal del servidor activo |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Definir mecanismo de autoarranque (servicio de Windows vs app en startup) — owner: Eng lead — 2026-10-07

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
