# Change Request (CR) — CR-120 — Twinpad

| | | |
|---|---|---|
| ID | CR-120 |
| Title | Home físico es el Guide/PS del mando (no un clic de stick), un solo transporte de input por reporte y conexión BT HID a todos los emparejados |
| Type | `Bug fix / Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 (§8.3, §16), SPEC-011 (nota en §4) |
| Sections | SPEC-004 §8.3, §16; SPEC-011 §4 |
| Current version → new version | SPEC-004 1.14.0 → 1.15.0 · SPEC-011 0.2.3 → 0.2.4 |

## 2. Requested change

Reporte del owner (chat, 2026-11-26): «1. El botón Home no está cableado; 2. No
está para asignar en el mapper físico tampoco; 3. Cuando conecto el celular por
BT Steam tarda muchísimo en detectarlo como control; 4. Si tengo una integración
abierta como CEMU y lo abrí directamente desde Steam con BT lo detecta como doble
input». Aclaración posterior: «Home no va hacia CEMU, el botón Home funciona como
Home de Xbox o el PS del DualShock».

**Current (código, SPEC-004 1.14.0 / SPEC-011 0.2.3):**

```text
- PhysicalKeys.BUTTON_L3 = 110 y BUTTON_R3 = 111. En Android 110 es
  KEYCODE_BUTTON_MODE (Home/Guide/PS) y 111 no es un botón de mando; los
  sticks son BUTTON_THUMBL = 106 y BUTTON_THUMBR = 107. Pulsar Home se
  traducía a un clic del stick izquierdo y L3 real no existía.
- GamepadMapping.DEFAULT no asignaba extra_1: no había fila utilizable para
  Home y la fila se rotulaba «Botón extra 1».
- PairingViewModel.onGamepadInput enviaba el reporte por BT y, si la sesión
  estaba Linked y el reporte llevaba motion o touch, lo reenviaba completo
  por LAN: el juego veía dos mandos (HID Bluetooth del SO + snapshot del
  plugin en Cemu).
- BluetoothHidGamepad probaba un emparejado clásico por reintento con
  backoff 4 s → 20 s: con varios equipos emparejados el PC quedaba al final
  de la rotación y Steam tardaba minutos en listar el gamepad.
```

**Proposed:**

```text
SPEC-004 §8.3 — un único transporte por reporte:
- el input_report de cada cambio sale por UNA ruta: BT HID si el canal está
  operativo; LAN si el canal BT no lo está y la sesión está Linked; si no
  hay ninguna, H0 y no se envía;
- mientras el canal BT está operativo, la sesión LAN NO recibe input_report.
  Consecuencia asumida por el owner: touch DRC e inercia dejan de llegar al
  plugin en ese modo (no viajan por BT); sin BT la ruta LAN sigue completa.
  Extender el descriptor para transportarlos por BT exige otro CR.

SPEC-011 §4 — Home:
- extra_1 es el botón Home del mando (Guide de Xbox / PS del DualShock) y su
  keycode físico estándar es KEYCODE_BUTTON_MODE (110);
- la fila del mapeador se rotula «Home» y el mapeo predeterminado asigna
  Home → extra_1 (sin keycode, la pulsación se perdía);
- L3/R3 usan BUTTON_THUMBL (106) y BUTTON_THUMBR (107); 110 deja de ser L3.
```

## 3. Motivation

Home es un control de sistema del mando, no un botón del juego: el owner lo
quiere como Guide/PS (Steam y el SO), no como input de Cemu —eso ya lo cumple
el adaptador Cemu, que ignora los dos botones extra—. El defecto real estaba en
la traducción del keycode: Home se entregaba como L3. Los otros dos síntomas
son consecuencias directas del envío duplicado BT+LAN y de la rotación lenta de
candidatos Bluetooth.

## 4. Impact analysis

| Area | Impact |
|---|---|
| SPEC-004 §8.3 | Un solo transporte por reporte; la LAN no recibe input_report mientras haya BT operativo |
| SPEC-004 §2/§4 | Sin cambio: Signature, campos y `seq` intactos |
| SPEC-011 §4 | Home = extra_1 (BUTTON_MODE); rótulo «Home»; L3/R3 con sus keycodes |
| REQ-003 AC-1/AC-2 | Sin cambio: el juego sigue recibiendo el gamepad por una ruta |
| Rumble | Sin cambio: sigue por BT (informe de salida, §8.2) |
| Touch / giro (Cemu) | No llegan al plugin mientras el canal BT esté operativo; con BT caído vuelven por LAN |
| Tests | TEST-0213 nuevo (keycodes, Home por defecto, fila asignable); TEST-0127 (HandoverPolicy) sin cambio de contrato |
| Código | `GamepadMapping`, `PhysicalGamepadAlgo`, `GamepadMapperDialog`, `PairingViewModel`, `BluetoothHidGamepad` |

## 5. Options considered

1. **BT primero con un único transporte (elegida).** Coincide con la prioridad
   de CR-111 y elimina el doble input sin inventar un modo nuevo.
2. Mantener el envío LAN de motion/touch con el reporte completo — descartada:
   el reporte es una instantánea completa (SPEC-004 §4), así que reenviarlo
   duplica botones y sticks (el defecto reportado).
3. Neutralizar botones/sticks en el reporte LAN — descartada: inventa un estado
   que ningún SPEC define y rompe combinaciones touch+botón.
4. Rotación de candidatos BT con backoff más corto — insuficiente: el host
   correcto seguía esperando turnos.
5. **Conectar a todos los emparejados clásicos en cada ronda (elegida).**

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner reporta los cuatro síntomas y define que Home debe
  comportarse como el botón de sistema del mando.
- **Rollout:** misma release; sin migración de datos. Los perfiles guardados de
  mando siguen válidos (solo cambia el keycode con el que se captura Home).

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-004 1.15.0 §8.3/§16; SPEC-011 0.2.4 §4)
- [x] Dependent documents updated (nota en este CR; CR-111 queda revisado por §8.3)
- [x] Tests added/updated (TEST-0213)
- [x] `testing/traceability.md` rows updated
- [x] Indexes (`docs/process/change-requests/_index.md`) updated
- [x] Code change applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado con el reporte del owner |
