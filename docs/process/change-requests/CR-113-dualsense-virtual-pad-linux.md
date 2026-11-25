# Change Request (CR) — CR-113 — Twinpad

| | |
|---|---|
| ID | CR-113 |
| Title | Mando virtual DualSense opcional en el Servidor PC (Linux, `/dev/uhid`) para que el SO y Steam vean un gamepad propio |
| Type | Feature (servidor) |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 (nueva §8.4), CR-095 §5 (nota de revisión), ADR-009 (nota de escalamiento) |
| Sections | SPEC-004 §8.4 (nueva), §16 (verificación) |
| Current version → new version | SPEC-004 1.12.0 → 1.13.0 |

## 2. Requested change

**Current:** CR-095 §5 descartó el mando virtual del Servidor («Descartada por el
owner») y ADR-009 §7 lo dejó como escalamiento (ViGEm/UHID) solo si el owner lo
pedía. Por eso el Servidor no puede presentar Twinpad con VID/PID propio: el HID
por Bluetooth usa el descriptor genérico (sin VID/PID en el SDP, CR-095 §3) y
Windows/Steam lo enganchan como mando genérico, sin botón PS, touchpad ni
giroscopio reconocidos.

**Proposed:** el owner autoriza el mando virtual **solo en el Servidor PC** (la app
no cambia): un DualSense virtual por `/dev/uhid` (Linux) que reenvía la Signature.
Se activa por configuración del servidor (`TWINPAD_VIRTUAL_PAD`) y **no** sustituye
al transporte de §8.3: consume los `input_report` que llegan por LAN.

Texto normativo que se añade a SPEC-004:

### 8.4 Mando virtual del Servidor (opcional, solo Linux; CR-113)

- **Trigger:** el Servidor PC arranca con `TWINPAD_VIRTUAL_PAD=dualsense`
  (también `ds5`, `1` o `true`) y el kernel expone `/dev/uhid` escribible.
- **Flow:** por cada `input_report` aceptado del dueño de inputs de la sesión el Servidor
  actualiza un DualSense virtual (report id `0x01`, 64 bytes, repetido a 100 Hz)
  con sticks, D-pad, los 12 botones —`Home` → `PS`, `extra_2` → click del
  touchpad—, triggers, touch y muestras inerciales; responde los feature reports
  `0x05` (calibración), `0x09` (emparejamiento) y `0x20` (firmware) que pide el
  host. Al terminar la sesión deja el mando en neutro.
- **Result:** el SO de la PC lista «Twinpad DualSense» (VID `054C`, PID `0CE6`,
  bus USB) con touchpad, giroscopio, acelerómetro y botón PS; Steam lo reconoce
  como DualSense. Sin la variable, sin `/dev/uhid` o sin permiso sobre él, el
  Servidor opera igual y no hay mando virtual: su ausencia no es un error de
  sesión ni cambia el resto del contrato.
- **Nota:** solo consume los `input_report` de la sesión LAN. Mientras el
  transporte de §8.3 (BT HID) esté operativo, esos reportes no llegan al
  Servidor y el mando virtual queda en reposo; forzar la ruta LAN exigiría un
  cambio en la app, fuera del alcance de este CR.

## 3. Motivation

El owner pidió que Twinpad se identifique como un mando propio (no genérico) para
que Steam reconozca giroscopio, touchpad y botón Home. Por Bluetooth eso es
imposible (SDP sin VID/PID); el único camino es un HID virtual en la PC, que
CR-095 §5 había descartado. El owner lo autoriza ahora y elige alcance
«CR + backend Linux (UHID)», modo «solo servidor, sin tocar la app».

## 4. Impact analysis

| Area | Impact |
|---|---|
| REQ-003 | Sin cambio: la Signature y sus 12 botones no cambian; el mando virtual es un consumidor más del `input_report`. AC-1 (el SO lista el gamepad) pasa a cumplirse también por esta vía opcional. |
| SPEC-004 | Nueva §8.4; sin cambios en §4, §8.1–§8.3, §9 ni §14. |
| ARCH §2 | El Servidor PC gana un adaptador HID de salida opcional; no cambia la frontera TCP/NDJSON ni la autoridad de sesión. |
| NFR-004 | Sin dependencias nuevas: `std::fs` sobre `/dev/uhid` y `getrandom` ya presente. Sin `unsafe`. |
| CR-095 §5 | La opción 1 («mando virtual, requiere permisos de sistema») deja de estar descartada: queda como modo opt-in documentado aquí. |
| ADR-009 | El escalamiento UHID/ViGEm de §7 se materializa para Linux vía UHID, sin driver externo. |
| Permisos | Requiere `/dev/uhid` accesible por el usuario del servidor (udev `uaccess`/regla). Sin permiso: se registra el aviso y el servidor sigue. |
| Riesgo abierto | La escala y el orden de ejes inerciales (Android → DualSense) se portan de inputtino y no se pudieron validar contra Steam en banco físico desde este entorno; se verifican en la primera prueba real. |

## 5. Options considered

1. **Mando virtual DualSense por UHID en el Servidor (Linux).** Elegida: VID/PID
   reales, bt/PS, touchpad y motion; sin driver externo ni permisos de uinput.
2. Mando virtual por ViGEm/uinput con driver externo — descartada: instalación y
   permisos extra en Windows/Linux.
3. Mantener solo BT HID genérico — descartada: Steam no reconoce PS/touch/gyro.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner autoriza explícitamente el mando virtual en el servidor
  y elige el backend Linux por UHID, dejando la app intacta.
- **Rollout:** opt-in por variable de entorno; sin la variable el comportamiento
  es idéntico al actual (SPEC-004 1.12.0). Sin migración de datos.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-004 1.13.0, §8.4 + changelog)
- [x] Dependent documents updated (CR-095 §5 y ADR-009 con nota de revisión)
- [x] Tests added/updated (TEST-0203)
- [x] `testing/traceability.md` rows updated
- [x] Indexes (`_index.md`) updated
- [x] Code: `server/src/virtual_pad.rs`, cableado en `server/src/main.rs` y
      `server/src/lib.rs`

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Abierto y aprobado por el owner en la misma sesión; implementado el backend Linux |
