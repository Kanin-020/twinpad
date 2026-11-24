# Change Request (CR) — CR-110 — Twinpad

| | |
|---|---|
| ID | CR-110 |
| Title | Añadir el nivel de batería del teléfono al reporte Bluetooth HID (Battery Strength) |
| Type | Scope change |
| Status | Open |
| Author | Buffy (agente) |
| Created | 2026-11-24 |
| Decided | - |
| Decided by | - |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 (§2 Signature/Report Map, §4 transporte BT), DES-005 (descriptor) |
| Sections | SPEC-004 §2, §4 |
| Current version → new version | SPEC-004 1.11.0 → 1.12.0 (propuesta) |

## 2. Requested change

**Current:** el Report Map BT (CR-059) declara 15 botones y 2 triggers, sin batería.

**Proposed:** el Report Map BT añade un campo de entrada tras el pad de 5 bits:

```text
Generic Device Controls (0x06) / Battery Strength (0x20)
Logical 0…100 (porcentaje), 8 bits, Input Data Var Abs.
Report ID 1, byte 10 del reporte.
```

El valor es el porcentaje de batería del teléfono (`ACTION_BATTERY_CHANGED`,
`level * 100 / scale`), clampeado a 0…100. El reporte pasa de 10 a 11 bytes.

## 3. Motivation

El usuario pidió que Steam muestre el nivel de batería del teléfono cuando
Twinpad actúa como mando Bluetooth. Hoy la app no lee la batería del teléfono
y el reporte BT no la incluye.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-004 | yes | §2 y §4 (tamaño del reporte) | update tras aprobación |
| DES-005 | yes | nuevo campo del Report Map | update |
| TEST-0126 | yes | tamaño del reporte y byte 10 | update (ya actualizado en código) |
| Code | yes | SignatureHidCodec, GamepadBtSwitcher | implementado, pendiente de aprobación |
| Integrations | yes | Steam/SO deben aceptar el campo | banco físico |

## 5. Options considered

1. Campo Battery Strength en el Report Map — estándar HID, lo lee el SO.
2. Enviar la batería por LAN — no cumple: el gamepad sólo viaja por BT (SPEC-004 §8.3).
3. No enviarla — incumple el pedido del usuario.

## 6. Decision

- **Outcome:** Pendiente.
- **Rationale:** Battery Strength es el usage estándar para batería de dispositivos HID.
- **Rollout:** Implementado en código a la espera de la aprobación del owner; verificar en banco con Steam.

## 7. Execution checklist

- [ ] Aprobación del owner
- [ ] Actualizar SPEC-004 §2/§4 y DES-005
- [x] Campo en el codec y report map (TEST-0126 actualizado)
- [x] Lectura de batería del teléfono y envío por BT
- [ ] Verificar en banco con Steam
