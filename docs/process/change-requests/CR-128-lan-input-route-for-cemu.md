# Change Request (CR) — CR-128 — Twinpad

| | |
|---|---|
| ID | CR-128 |
| Title | Ruta LAN del gamepad cuando el receptor es Cemu (el Plugin no consume el HID del sistema) |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner (petición directa: «Abre un CR y haz que el gamepad use LAN cuando el receptor es Cemu») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-004` |
| Sections | `§8.3` (flow/result), `§8.4` (nota) |
| Current version → new version | `1.18.0 → 1.19.0` |

## 2. Requested change

**Current:**

```text
§8.3 Flow: cada reporte sale por **una sola** ruta (CR-120). Con el canal BT HID operativo, el
     reporte se envía por BT y la sesión LAN no recibe `input_report`; si el canal BT no está
     operativo y la sesión está Linked, el reporte completo (...) sale por LAN.
§8.4 Nota: solo consume los `input_report` de la sesión LAN. Mientras el transporte de §8.3
     (BT HID) esté operativo, esos reportes no llegan al Servidor y el mando virtual queda en reposo;
     forzar la ruta LAN exigiría un cambio en la app, fuera del alcance de CR-113.
```

**Proposed:**

```text
§8.3 Flow: cada reporte sale por **una sola** ruta (CR-120). Con un receptor que **no consume el
     HID del sistema** —el Plugin Cemu (`game_id` `cemu`) lee los inputs por LAN— y la sesión
     Linked, la ruta es **LAN** aunque el canal BT esté operativo (CR-128); sin sesión, el BT sigue
     siendo la ruta. Para el resto de receptores se mantiene BT primero y LAN como respaldo.
§8.3 Result: con Cemu los inputs llegan por LAN (el Plugin los consume) y el BT queda como respaldo
     sin sesión; para el resto, BT HID y, si no está operativo, LAN.
§8.4 Nota: consume los `input_report` de la sesión LAN. Con Cemu seleccionado la ruta de §8.3 ya es
     LAN (CR-128) y el mando virtual refleja los inputs; con otros juegos, mientras el transporte BT
     HID esté operativo, esos reportes no llegan al Servidor y el mando virtual queda en reposo.
```

## 3. Motivation

Diagnóstico en vivo del 2026-11-26 con Cemu y la app vinculados:

- El servidor recibió por 8420 **+48 B en 8 s** (sólo pings/pongs): ningún `input_report` desde el arranque de la sesión.
- El servidor sólo envió **364 B** a Cemu por 8421: el plugin nunca recibió estado de mando.
- El PC **sí** listaba el gamepad BT del teléfono (`/dev/input/js0`, «POCO X8 Pro», uhid), luego `btReady` era verdadero y `HandoverPolicy.route` mandaba el 100 % de los reportes por BT.
- Cemu consume el mando por el **plugin Twinpad**, que recibe el `input_report` por LAN (8421) y no lee el HID del SO.

CR-120 eligió «una sola ruta, BT primero» para evitar el doble mando y es correcto para juegos que usan el HID del sistema (Steam, SO), pero deja sin inputs al receptor Cemu. El `game_id` `cemu` ya gobierna otras funciones exclusivas del plugin (cámara, micrófono, teclado, Amiibo), así que es la señal disponible sin wire nuevo.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | AC-2 (inputs llegan al juego) se cumple mejor; sin cambio de requisito | none |
| SPEC-004 | yes | §8.3 y nota de §8.4: excepción Cemu con LAN preferente | update (este CR) |
| ARCH | no | Mismo `input_report` y mismos canales | none |
| DES-002 / DES-004 / DES-005 | no | Describen handover y plugin; el primero se actualiza con el código | none |
| TEST | yes | TEST-0225: casos `preferLan` de `HandoverPolicy` | new row |
| Code | yes | `hid/HandoverPolicy` (parámetro `preferLan`), `pairing/GamepadBtSwitcher`, `pairing/PairingViewModel` (juego `cemu`) | ~15 líneas |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Wire intacto; el plugin ya consume `input_report` | none |

## 5. Options considered

1. **LAN preferente con `game_id` = `cemu` y sesión Linked** — usa la señal que ya existe, sin wire nuevo, y conserva BT como respaldo.
2. **Elegir la ruta en el drawer** — preferencia manual nueva, más superficie de UI y estado persistido.
3. Do nothing — Cemu sigue sin inputs mientras el canal BT esté operativo.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el receptor Cemu nunca puede consumir el HID del sistema; la excepción es determinista con el `game_id` ya seleccionado y mantiene una sola ruta por reporte (CR-120).
- **Rollout:** misma release; sin migración.

## 7. Execution checklist

- [x] Target document(s) updated (versión 1.19.0, changelog con este CR)
- [x] Dependent documents updated (nota §8.4 incluida)
- [x] Tests added/updated (TEST-0225)
- [x] `testing/traceability.md` rows updated (incluida la tabla de CRs)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (si aplica)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado por petición directa del owner; aplicado en SPEC-004 1.19.0 y código |
