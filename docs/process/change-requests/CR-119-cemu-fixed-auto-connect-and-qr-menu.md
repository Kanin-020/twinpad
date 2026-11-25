# Change Request (CR) — CR-119 — Twinpad

| | |
|---|---|
| ID | CR-119 |
| Title | Cemu: conexión automática fija, menú de solo configuraciones y H.264 por defecto |
| Type | Behavior change |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat: petición de los ajustes de configuración de Cemu) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 §1/§3/§12/§16 y R4/R5/R10, DES-004 §1–§8, traceability |
| Sections | R4, R5, R10, §1, §3, §12, §16 |
| Current version → new version | pendiente de actualización documental |

## 2. Requested change

**Current:** Cemu persiste `twinpad_auto_connect` (false por defecto) y ofrece
«Iniciar conexión», que inicia/reutiliza el Servidor PC y presenta el QR de
Pairing. `twinpad_hardware_acceleration` también es false por defecto.

**Proposed (decisión final):**

- La conexión automática deja de ser una preferencia: al cargar un título Cemu
  arranca el cliente Twinpad y solicita la conexión siempre. Se retiran la clave
  `twinpad_auto_connect` y el checkbox «Conexión automática».
- El menú Twinpad queda únicamente con «Configuraciones». Se eliminan «Iniciar
  conexión», el diálogo QR, la consulta `GET_QR`/`ROTATE_QR` y el arranque o
  cierre de `twinpad-server` desde Cemu. Cemu no gestiona el proceso del
  Servidor ni muestra el QR; usa el servidor que ya esté disponible.
- `twinpad_hardware_acceleration` pasa a true por defecto (la preferencia sigue
  siendo configurable y desactivable).

Esta decisión reemplaza el alcance anterior de este CR, que dedicaba «Iniciar
conexión» a mostrar y rotar el QR.

## 3. Motivation

Petición directa del owner: la conexión automática debe ser el comportamiento
normal, Cemu no debe mostrar QR ni ofrecer un inicio de conexión manual, el menú
debe limitarse a Configuraciones y la aceleración H.264 debe venir activa salvo
que el usuario la apague.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-006 | yes | R4 (auto-connect opt-in), R5/R10 (host inicia servidor y presenta QR) y el escenario de Pairing describen el flujo anterior | update |
| DES-004 | yes | §1–§4 y §6–§8 documentan `twinpad_auto_connect`, el menú QR y la gestión del servidor desde Cemu | update |
| TEST-046 | yes | La aceptación describe la preferencia opt-in, el menú QR y el inicio manual; pasa a cubrir auto-conexión fija y menú de solo Configuraciones | update |
| TEST-047 | no | El canal QR del Servidor sigue existiendo para otros hosts; Cemu deja de consumirlo y se retira `ROTATE_QR` | reverted |
| Code | yes | Cemu (menú, cliente y config); servidor vuelve a aceptar solo `GET_QR` en el canal IPC | applied |
| Data / migrations | no | La clave antigua `twinpad_auto_connect` se ignora y deja de escribirse | none |
| Integrations / contracts | yes | Se retira el comando loopback `ROTATE_QR`; `GET_QR` permanece para la web local y Decky | none |

## 5. Options considered

1. Mantener un menú QR separado — no cumple la decisión de dejar solo
   Configuraciones y conserva la gestión del servidor en Cemu.
2. Conexión automática fija, menú de solo Configuraciones y QR únicamente en la
   interfaz local del Servidor — un solo flujo en Cemu y sin IPC adicional.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** El owner pidió explícitamente el alcance final y descartó el QR
  y el inicio manual en Cemu.
- **Rollout:** misma release; sin migración de datos.

## 7. Execution checklist

- [x] Código aplicado
- [x] `ROTATE_QR` retirado y TEST-047 restaurado
- [x] SPEC-006 y DES-004 actualizados
- [x] TEST-046 actualizado en la matriz
- [x] Índice de CRs actualizado

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Abierto y aprobado con la petición inicial |
| 0.2.0 | 2026-11-25 | Buffy (agente) | Alcance final: sin QR ni inicio manual en Cemu |
