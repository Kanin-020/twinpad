# Change Request (CR) — CR-108 — Twinpad

| | |
|---|---|
| ID | CR-108 |
| Title | Escanear un QR nuevo desde U4 (sesión activa) sin cerrar la sesión |
| Type | Behavior change |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-23 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 (§6, §14, R5), DES-002 (panel de vinculación con sesión activa) |
| Sections | SPEC-002 §6 (tabla y nota de invariante), §14 (invariantes), R5 (§11); DES-002 (vista «Nueva conexión») |
| Current version → new version | `1.13.0 → 1.14.0` (a fijar por el PO) |

## 2. Requested change

**Current:**

```text
Invariante (§6): U4 es el único estado con sesión activa; ...
§14: U4 solo se alcanza con un pair_ack recibido y verificado; ...
R5: Un intento de vinculación = un escaneo; ...
Pantalla de Pairing con sesión activa: solo se muestra la sesión; no hay acción de escaneo.
```

**Proposed (decisión del owner, 2026-11-25 — «se registra pero no se hace»):**

```text
§6 (nueva fila): U4 | enlace pedido (QR o código) | U4 | el payload queda
  registrado como enlace pendiente; la sesión activa no se toca.
§6 (nueva transición): U4 → U1/U5 (la sesión termina, por desconexión o caída)
  | si hay un enlace pendiente, se inicia con él el intento de vinculación
  (misma ruta que un escaneo desde U1) y el pendiente se consume una sola vez.
§6 (estado transitorio): U6 (Reconectando) todavía cuenta como sesión viva: el
  pendiente NO se ejecuta hasta que la reconexión se resuelva.
§14: U4 solo se alcanza con un pair_ack recibido y verificado (SPEC-001 §7);
  jamás por timeout ni por escaneo solo. [sin cambios]
  Un enlace pendiente no crea sesión ni intento paralelo: hasta que la sesión
  no termina, la máquina sigue en U4.
R5: Un intento de vinculación = un escaneo (o un código escrito). Sin sesión
  viva el intento es inmediato; con sesión viva queda registrado y se ejecuta
  al terminar la sesión. Nunca hay reintentos automáticos.
```

Alcance en la app (implementado):
- El panel «Nueva conexión» muestra QR y «Vincular sin cámara» con sesión viva.
- La cámara la abre la Activity (la máquina no acepta Escanear desde U4) y se
  cierra al registrarse el enlace; el aviso indica que se conectará al terminar
  la sesión actual.
- El pendiente vive en memoria del `PairingViewModel` (`core/PendingLink`): la
  muerte del proceso lo descarta.

## 3. Motivation

El rework de UI pide que «Nueva conexión» muestre el panel con el QR aunque haya sesión activa. Hoy `PairingStateMachine.onLinked` ignora `ScanRequested` (SPEC-002 §6 no define la transición U4 → U2), así que el botón de escanear se oculta en U4 y el panel no puede vincular otro dispositivo. Evidencia: `PairingActivity` oculta `btn_scan` mientras `showingSession` es verdadero.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no revisado | Revisar si el AC de vinculación cubre escaneo con sesión activa | verificar antes de aprobar |
| SPEC-002 | yes | Nueva transición U4 → U2 y texto de invariante | update vía este CR |
| SPEC-001 | no | El wire no cambia; el handshake sigue igual | none |
| DES-002 | yes | Panel de vinculación visible desde «Nueva conexión» con sesión viva | update |
| TEST-010, TEST-014, TEST-053 | yes | Casos de escaneo solo desde U1 | revisar y añadir TEST nuevos con fila en traceability |
| Code | yes | `PairingStateMachine.onLinked` (acepta `ScanRequested`), `PairingViewModel`, `PairingActivity` (mostrar `btn_scan` en U4) | implementar tras aprobar |
| Tests de la máquina | yes | `PairingStateMachineTest` y `PairingViewModelTest` con `ScanRequested` en U4 | actualizar |

## 5. Options considered

1. Permitir `ScanRequested` desde U4 → U2, cerrando la sesión anterior solo si el nuevo intento llega a `pair_ack` — es la opción que da al usuario el flujo que pidió.
2. Mantener la sesión viva hasta que el nuevo intento llegue a `pair_ack`, y restaurarla si falla — evita perder la sesión, pero añade una máquina paralela que el SPEC no define.
3. No hacer nada — «Nueva conexión» sigue sin QR con sesión activa; el usuario debe desconectar primero.

## 6. Decision

- **Outcome:** aprobada 2026-11-25. Con sesión activa, el QR/código se muestra y el enlace pedido se **guarda** («se registra») pero **no se ejecuta** hasta que la sesión termine.
- **Rationale:** el owner quiere poder vincular el siguiente dispositivo sin cortar la sesión en curso; la sesión viva nunca se cierra para atender el escaneo.
- **Rollout:** cambio local de la app; sin cambios de wire ni de servidor.

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID) — pendiente: el agente no modifica SPECs
- [ ] Dependent documents updated (DES-002 «Nueva conexión»)
- [x] Tests added (TEST-0207 `PendingLinkTest`)
- [x] `testing/traceability.md` row updated (incluida la tabla de historial de cambios)
- [ ] Indexes (`_index.md`) updated
- [x] Code applied: `core/PendingLink`, `PairingViewModel` (registro y consumo), `PairingActivity` (panel QR/código con sesión viva)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-23 | Buffy (agente) | Opened |
| 0.2.0 | 2026-11-25 | Buffy (agente) | Decisión del owner: enlace pendiente que se ejecuta al terminar la sesión; implementado con TEST-0207 |
