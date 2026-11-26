# Change Request (CR) — CR-125 — Twinpad

| | |
|---|---|
| ID | CR-125 |
| Title | Watchdog de la conexión BT HID: una ronda colgada se aborta a los 10 s y se reintenta sola |
| Type | Correction |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Product owner (chat: «Implementa el watchdog de conexión HID con su CR para que la app reintente sola si el host deja el enlace colgado») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 §8.3 |
| Sections | §8.3 |
| Current version → new version | `1.17.0 → 1.18.0` |

## 2. Requested change

**Current:** una ronda de conexión HID queda sin desenlace si
`BluetoothHidDevice.connect()` devuelve `true` y el stack no emite ningún
callback de estado (page o L2CAP colgado). `scheduleConnectRetry()` solo se
arma en `DISCONNECTED` o cuando ningún `connect()` fue aceptado, así que el
canal se queda en `REGISTERED` sin reintentar hasta reiniciar la app.

Evidencia en el teléfono del owner (2026-11-26): tras
`estado de enlace HID con el host: 1`, los reintentos de 1 s, 2 s y 3 s y
después 7 minutos sin ningún intento ni log nuevo, con el host aún conectado
por Bluetooth.

**Proposed (SPEC-004 §8.3):**

```text
Watchdog de conexión (CR-125): cada ronda de conexión HID —también la que
inicia el host— arma una ventana de 10 s. Si vence sin llegar a CONNECTED,
la app aborta los enlaces pendientes con disconnect() y reprograma la ronda
con el backoff de 1,5 s a 8 s. Al conectar, al caer el enlace o al detenerse
el canal la ventana se cierra y no vence.
```

## 3. Motivation

Implementation discovery sobre el diagnóstico del enlace colgado: el
`connect()` aceptado sin callbacks deja la máquina de estados sin ningún
temporizador que la desbloquee. El owner pide que la app reintente sola.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-004 | yes | §8.3 documenta el watchdog y su ventana | update vía este CR (1.18.0) |
| ARCH / ADR | no | sin cambio de límites ni de transporte | none |
| HID / wire | no | Report Map, reportes y estados del SO intactos | none |
| Código | yes | `hid/BluetoothHidGamepad` arma/cierra la ventana y `hid/HidConnectWatchdog` (puro) | implementado |
| Tests | yes | TEST-0218 nuevo (JVM) | actualizado en `traceability.md` |
| Datos | no | sin preferencias nuevas | none |

## 5. Options considered

1. **Watchdog de 10 s + aborto + reintento con el backoff existente (elegida).**
   Desbloquea cualquier ronda sin callbacks sin tocar el contrato HID.
2. Reintentar solo al recibir `STATE_CONNECTING` — descartada: el caso
   reportado es precisamente la ausencia de callbacks.
3. Desregistrar y recrear el canal al vencer — descartada: CR-122 midió que
   recrear el perfil cierra enlaces recién abiertos y retrasa la detección.
4. Do nothing — el canal puede quedar muerto hasta reiniciar la app.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pide el reintento autónomo; 10 s cubre el page
  timeout del stack y cualquier ronda legítima, y el reintento reutiliza el
  backoff ya validado de 1,5 s a 8 s.
- **Rollout:** misma release; sin migración.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-004 1.18.0, §8.3 y changelog)
- [x] Dependent documents updated (esta tabla de impacto)
- [x] Tests added/updated (TEST-0218, `HidConnectWatchdogTest`)
- [x] `testing/traceability.md` rows updated (incluida la tabla de historial de CR)
- [x] Indexes (`docs/process/change-requests/_index.md`) updated
- [x] Code change applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado con la petición del owner; código, SPEC y tests aplicados |
