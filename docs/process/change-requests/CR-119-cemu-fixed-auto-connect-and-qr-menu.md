# Change Request (CR) — CR-119 — Twinpad

| | |
|---|---|
| ID | CR-119 |
| Title | Cemu: conexión automática fija, menú de solo QR y H.264 por defecto |
| Type | Behavior change |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat: petición de los tres ajustes de configuración de Cemu) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 §10/§11 (R4/R5), DES-004 §3/§4/§8 |
| Sections | R4, R5, §3, §4, §8 |
| Current version → new version | pendiente de actualización documental |

## 2. Requested change

**Current:** Cemu persiste `twinpad_auto_connect` (false por defecto); la conexión
manual solo está disponible con un título activo, solicita la conexión y espera
`cemu_live=true` antes de mostrar el QR. `twinpad_hardware_acceleration` también
es false por defecto.

**Proposed:**

- La conexión automática deja de ser una preferencia: al cargar un título Cemu
  arranca el cliente Twinpad y solicita la conexión siempre. Se retiran la clave
  `twinpad_auto_connect` y el checkbox «Conexión automática».
- «Iniciar conexión» queda siempre disponible y solo presenta el QR. Si el QR
  IPC (8423) no responde, inicia `twinpad-server` desde PATH y espera el QR; no
  crea una solicitud de conexión ni exige un título activo. Con el servidor
  activo, presenta el QR vigente y ofrece «Generar nuevo QR», que rota el token
  mediante el nuevo comando IPC `ROTATE_QR`.
- `twinpad_hardware_acceleration` pasa a true por defecto (la preferencia sigue
  siendo configurable y desactivable).

## 3. Motivation

Petición directa del owner: la conexión automática debe ser el comportamiento
normal, el menú debe servir para mostrar/renovar el QR sin iniciar una conexión
manual, y la aceleración H.264 debe venir activa salvo que el usuario la apague.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-006 | yes | R4 (auto-connect opt-in) y R5 (espera `cemu_live` antes del QR) describen el flujo anterior | update |
| DES-004 | yes | §3 lista `twinpad_auto_connect`; §4 y §8 documentan el inicio manual condicionado al título y la espera de `cemu_live` | update |
| TEST-046 | yes | La aceptación describe la preferencia opt-in y el inicio manual condicionado; pasa a cubrir auto-conexión fija y menú de QR | update |
| TEST-047 | yes | Se extiende el test del canal QR para cubrir `ROTATE_QR` (mismo TEST ID) | applied |
| Code | yes | Cemu (cliente, menú y config) y servidor (canal IPC 8423) | applied |
| Data / migrations | no | La clave antigua `twinpad_auto_connect` se ignora y deja de escribirse | none |
| Integrations / contracts | yes | Comando loopback `ROTATE_QR` en el puerto 8423; sin cambios en el wire de sesión | none |

## 5. Options considered

1. Mantener la preferencia opt-in y añadir otro botón de QR — conserva dos
   caminos y no cumple la petición de que la auto-conexión sea el defecto.
2. Hacer fija la auto-conexión, dedicar «Iniciar conexión» al QR y rotar el
   token por el canal IPC existente — un solo flujo por acción y sin depender de
   la interfaz web local.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** El owner pidió explícitamente los tres cambios de comportamiento.
- **Rollout:** misma release; sin migración de datos.

## 7. Execution checklist

- [x] Código aplicado (Cemu `f443a0ec`, servidor `3503feb9`)
- [x] TEST-047 extendido para `ROTATE_QR`
- [ ] SPEC-006 y DES-004 actualizados
- [x] TEST-046 actualizado en la matriz
- [x] Índice de CRs actualizado

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Abierto y aprobado con la petición del owner |
