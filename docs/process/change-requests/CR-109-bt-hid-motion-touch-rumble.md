# Change Request (CR) — CR-109 — Twinpad

| | |
|---|---|
| ID | CR-109 |
| Title | Extender el descriptor Bluetooth HID con giroscopio, touchpad táctil y rumble (output report) |
| Type | Scope change |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-23 |
| Decided | 2026-11-24 |
| Decided by | Owner (opción 1: gyro, touch y vibración por BT con descriptor genérico; fallbacks LAN sin cambios) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 (§2, §4, §8.2, §8.3, §11 R1, §17), DES-005 (descriptor) |
| Sections | SPEC-004 §2 Signature/Report Map, §4 transporte BT, §8.2 rumble, §8.3 exclusiones |
| Current version → new version | SPEC-004 1.11.0 → 1.12.0 (propuesta) |

## 2. Requested change

**Current (SPEC-004 §8.3):**

```text
Touch DRC, muestras inerciales y rumble siguen siendo únicamente de la ruta LAN/SDK;
extender el descriptor HID con ellos exige otro CR.
Report Map BT (CR-059): SIN touch DRC, giroscopio o rumble.
```

**Proposed:**

```text
El Report Map BT (CR-059/CR-109) incluye:
- Giroscopio y acelerómetro (Sensor usage page 0x20), en el mismo snapshot que motion
  (accel m/s², gyro rad/s, timestamp_us), usando los mismos marcos que §4.
- Touchpad táctil de un contacto (Digitizer page 0x0D): touch_x, touch_y, touch_pressed.
- Rumble como output report (Haptics/Generic vibration), que la app traduce a RumbleCodec.
Los tres son opcionales por dispositivo; si el host no los solicita, el reporte no los incluye.
```

## 3. Motivation

El usuario pidió gyro, touchpad en pantalla y vibración por el canal BT HID para que Steam
los reciba como un mando Bluetooth estándar. Hoy la app ya calcula estos datos en la ruta
LAN (InputReport.motion, touch, RumbleCodec/RumblePlayer), por lo que el cambio es de
descriptor y transporte, no de captura.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | mismos criterios de mando | none |
| SPEC-004 | yes | §2, §4, §8.2, §8.3, §11 R1 | update tras aprobación |
| ARCH / ADR-009 | yes | el transporte BT pasa a llevar más elementos | ADR o nota en ADR-009 |
| DES-005 | yes | nuevo Report Map | update |
| TEST-0126 | yes | el codec BT necesita casos de sensores, touch y output | update |
| Code | yes | SignatureHidCodec, BluetoothHidGamepad (callback de salida), GamepadBtSwitcher, RumblePlayer | PR pequeño por bloque |
| Integrations / contracts | yes | Steam/SO deben aceptar el descriptor extendido; Windows verlo como mando | banco físico |

## 5. Options considered

1. Extender el descriptor BT con gyro, touch y rumble — cumple lo pedido; requiere banco físico.
2. Mantener la exclusión y enviar gyro/touch solo por LAN — no cumple "como un mando Bluetooth cualquiera".
3. Hacerlo sin CR — viola SPEC-004 §8.3 y AGENTS.md.

## 6. Decision

- **Outcome:** Aprobado la opción 1 (2026-11-24): gyro, touch y vibración por BT HID con descriptor genérico (report IDs 2 y 3). Los fallbacks LAN no cambian. La opción DS4 queda descartada (ver Hallazgo).
- **Rationale:** Un descriptor genérico con usages de sensor (Sensors page 0x20) puede no llegar a Steam como giroscopio. Steam lee gyro/touch de un mando BT cuando el reporte imita el formato DualShock 4 (VID/PID y layout de reporte). Hay que elegir entre (a) descriptor compatible DS4 con gyro, touch y rumble, o (b) descriptor genérico que solo sirve para lectura directa del SO.
- **Rollout:** Pendiente de la decisión (a) o (b) y del banco físico con Steam.
- **Hallazgo (2026-11-23):** la opción (a) no es viable con la API pública de Android. `BluetoothHidDeviceAppSdpSettings` solo recibe name, description, provider, subclass y descriptor; no hay VID/PID. Sin VID/PID de Sony (054C/05C4 o 09CC) Steam no reconoce el mando como DS4 y no activa gyro/touch por ese formato. Queda la opción (b) o una alternativa fuera de la API pública, que requiere otro CR.

## 7. Execution checklist

- [ ] Aprobación del owner
- [ ] Actualizar SPEC-004, DES-005 y TEST-0126
- [ ] Implementar giro, touch y rumble en el codec y callback BT
- [ ] Verificar en banco (event del gamepad, output report de Steam)
