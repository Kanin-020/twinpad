# Change Request (CR) — CR-131 — Twinpad

| | |
|---|---|
| ID | CR-131 |
| Title | Vibración del DualSense virtual: el output report del host llega al teléfono del dueño de inputs por la sesión LAN |
| Type | New behavior |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner (petición en chat) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, SPEC-006 |
| Sections | SPEC-004 §8.4, §16; SPEC-006 §4, §12, §16 |
| Current version → new version | SPEC-004 1.20.0 → 1.21.0; SPEC-006 1.31.0 → 1.32.0 |

## 2. Requested change

**Current:** el mando virtual DualSense de CR-113/CR-114/CR-130 solo atiende
`UHID_GET_REPORT` en `/dev/uhid`; los output reports que el SO envía al mando
virtual (donde viaja el rumble del juego) se descartan. Como CR-130 suspende el
HID Bluetooth del teléfono mientras el mando virtual está activo, la vibración
no tiene ruta y el teléfono no vibra con los juegos que usan el DualSense
virtual.

**Proposed:** el Servidor lee los output reports USB (`report id 0x02`) del
DualSense virtual y reenvía las intensidades de sus motores al dueño de inputs
por la sesión autenticada, con un mensaje nuevo y estricto:

```json
{"type":"rumble_motors","strong":0,"weak":0}
```

- `strong` = `motor_left` (motor grande) y `weak` = `motor_right` (motor
  pequeño), ambos `0…255`.
- El mensaje se emite cuando el reporte tiene `valid_flag0 & 0x01`
  (motor/compatible) o `valid_flag2 & 0x04` (compatible). Un reporte con
  `valid_flag0`, `valid_flag1` y `valid_flag2` a cero se traduce en `0/0`
  (parar), como hace inputtino.
- Solo lo recibe la sesión dueña de inputs; sin sesión, sin dueño o sin
  vibrador se descarta sin afectar al mando ni a la sesión.
- El `rumble` de Cemu (CR-033) no cambia.

Texto normativo añadido a SPEC-004 §8.4:

```text
- **Vibración (CR-131):** el Servidor atiende los output reports USB (report id
  `0x02`, 48 bytes) que el host envía al DualSense virtual. Con
  `valid_flag0 & 0x01` o `valid_flag2 & 0x04` activos reenvía `motor_left` como
  `strong` y `motor_right` como `weak` al dueño de inputs mediante
  `rumble_motors` (SPEC-006 §4); con los tres flags a cero envía `0/0` para
  parar. La vibración no altera los inputs, el reporte de 100 Hz ni la ruta LAN.
```

Texto normativo añadido a SPEC-006 §4:

```text
| `rumble_motors` | mensaje Servidor→Android de sesión (CR-131) | `type:"rumble_motors"`, `strong` y `weak` enteros 0–255 exactamente, sin campos extra; `0/0` = parar | por cada output report `0x02` del DualSense virtual con rumble válido o con sus tres flags a cero; solo a la sesión dueña de inputs; no persistido; Android lo ignora si el dispositivo no tiene vibrador |
```

## 3. Motivation

El owner reportó que, con el mando virtual activo, Steam detecta el DualSense
virtual y los inputs funcionan, pero el teléfono no vibra. La causa es
verificable en `server/src/virtual_pad.rs`: el lector UHID solo procesa
`UHID_GET_REPORT` y descarta `UHID_OUTPUT`, que es donde llega el rumble que el
SO/Steam escribe al control. Es el último comportamiento pendiente del mando
virtual.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | AC-3 (rumble) ya exige que el teléfono vibre; esta CR le da una ruta cuando el dualsense virtual reemplaza al BT | none |
| SPEC-004 | yes | §8.4 gana la lectura del output report y el reenvío; §16 gana la verificación | update |
| SPEC-006 | yes | nuevo mensaje de sesión `rumble_motors`; §12 y §16 | update |
| ARCH | no | El mando virtual sigue siendo un adaptador del Servidor; el mensaje usa la sesión ya existente | none |
| DES-002/DES-004 | no | El render del mando virtual y el adaptador Cemu no cambian | none |
| TEST-0228..0230 | yes | Codec core, decodificación UHID del servidor y codec Android | add |
| Code | yes | `server/src/virtual_pad.rs`, `server/src/main.rs`, `core/src/game.rs`, Android (`RumbleMotorsCodec`, `PairingViewModel`) | small |
| Data / migrations | no | Sin persistencia ni cambios de preferencia | none |
| Integrations / contracts | yes | Nuevo mensaje NDJSON Servidor→Android; clientes antiguos lo ignoran como tipo desconocido y conservan el resto del contrato | version bump |

## 5. Options considered

1. **Mensaje `rumble_motors` con intensidades directas (elegida):** conserva la
   fuerza de cada motor y no mezcla el patrón de bits de Cemu (`rumble`) con
   una semántica distinta.
2. Reutilizar `rumble` con un patrón de bits generado a partir de los motores —
   descartada: pierde la intensidad y hace que un mensaje de Cemu y uno del
   DualSense virtual signifiquen cosas distintas.
3. No hacer nada — el mando virtual sigue sin vibración y AC-3 queda sin ruta
   mientras CR-130 mantiene el BT suspendido.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pide completar la vibración del mando virtual; el
  output report es la única fuente del rumble del juego y la sesión LAN ya es
  la ruta activa en ese modo.
- **Rollout:** misma release; sin migración. Sin mando virtual o sin output
  reports el comportamiento es idéntico al actual.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-004 1.21.0, SPEC-006 1.32.0)
- [x] Dependent documents updated (índice de CR)
- [x] Tests added/updated (TEST-0228/0229/0230)
- [x] `testing/traceability.md` rows updated
- [x] Indexes (`_index.md`) updated
- [x] Code: `core/src/game.rs`, `server/src/virtual_pad.rs`,
      `server/src/main.rs`, Android `RumbleMotorsCodec` + `PairingViewModel`

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado por la petición del owner; definido el mensaje `rumble_motors` y la lectura de `UHID_OUTPUT` |
