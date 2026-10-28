# Change Request (CR) — CR-032 — Twinpad

| | |
|---|---|
| ID | CR-032 |
| Title | Switch «Distribución Nintendo» en el drawer: botones de cara de la superficie táctil y del mando físico (A↔B, X↔Y) |
| Type | New behavior |
| Status | Approved |
| Author | Buffy (agent), a solicitud del Product owner |
| Created | 2026-10-28 |
| Decided | 2026-10-28 |
| Decided by | Product owner (por chat, 2026-10-28: «Tambien para los fisicos a la derecha b abajo etc») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002, SPEC-008 |
| Sections | SPEC-002 §11 (regla R19); SPEC-008 §11 (regla R5) |
| Current version → new version | SPEC-002 1.9.0 → 1.10.0; SPEC-008 0.1.0 → 0.2.0 |

## 2. Requested change

**Current:**

```text
SPEC-002: el drawer de ajustes expone resolución, escalado, bitrate, audio,
HUD fps, monitor gyro y controles táctiles; no existe ninguna preferencia
que altere el layout de los botones de cara.
SPEC-008: el perfil táctil fija las posiciones/etiquetas de los controles y
la Signature HID envía `button_a/b/x/y` tal cual se tocan o se pulsan; el
mando físico mapea su keycode A→bit A, B→bit B, X→bit X, Y→bit Y.
```

**Proposed:**

```text
SPEC-002 §11 R19 (nueva): el drawer de ajustes incluye el interruptor
«Distribución Nintendo» (preferencia local persistente, apagado por
defecto). Activado, el layout de los cuatro botones de cara se muestra y
se envía en distribución Nintendo: A a la derecha, B abajo, X arriba,
Y a la izquierda (intercambio A↔B y X↔Y respecto del default Xbox/Play).
Aplicar/desaplicar es inmediato, tanto para la superficie táctil como para
el mando físico conectado al teléfono (CR-028). El wire (Signature HID de
SPEC-004 §4) no cambia: el intercambio es entre bits	canónicos ya
definidos, sin campos ni códigos nuevos.

SPEC-008 §11 R5 (nueva): cuando la preferencia «Distribución Nintendo»
(SPEC-002 R19) está activada, el perfil aplicado presenta a/b/x/y en
distribución Nintendo (etiquetas e intercambio de los Elementos HID de los
cuatro botones de cara: el control en posición derecha envía button_a, el
de abajo button_b, el de arriba button_x, el de izquierda button_y); los
demás controles y las coordenadas del perfil no cambian. Desactivada, el
perfil se muestra y envía tal como fue guardado.
```

## 3. Motivation

El Product owner pidió por chat (2026-10-28) un switch en los ajustes de la
app llamado «distribución nintendo»: al activarlo, el mapping se comporta
como un mando de Nintendo y al apagarlo revierte a distribución Xbox/Play,
también «para los físicos». Con el protocolo de ambigüedad (ai-agent-rules
§3) el owner eligió el alcance y añadió la cobertura del mando físico.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-008 | no | la preferencia vive en el drawer (SPEC-002) y altera presentación/asignación de controles, ya cubiertos | none |
| SPEC-002 | yes | nueva regla R19 (preferencia del drawer) | bump 1.10.0 |
| SPEC-004 | no | Signature sin cambios: bits con intercambio entre elementos canónicos | none |
| SPEC-008 | yes | nueva regla R5 (presentación del perfil según preferencia) | bump 0.2.0 |
| ARCH | no | nada de boundaries | none |
| TEST-078..088 | no | tests existentes intactos | none |
| TEST-089/090 | yes | TEST-089: persistencia/dot del switch en el store; TEST-090: transformación de layout del perfil y swap físico (JVM) | add rows |
| Code | yes | `AndroidSettings(Store)`, `drawer_settings.xml`, `strings.xml`, `SettingsDrawerController`, host de `PairingActivity`, `ProfileSurface`, `PhysicalGamepadAlgo` | this PR |
| Integrations / contracts | no | wire sin cambios | none |

## 5. Options considered

1. **Switch de preferencia local que intercambia A↔B y X↔Y en presentación (táctil) y en el mapper físico (propuesta)** — una sola regla mental para el usuario, cero cambios de protocolo, reversible e instantánea.
2. Remapear los Elementos HID guardados dentro de cada perfil persistido — destructivo con los perfiles del usuario y frágil ante import/export (SPEC-007).
3. Do nothing — los mandos de Nintendo quedarían con A/B/X/Y cruzados respecto de lo que el jugador ve.

## 6. Decision

- **Outcome:** Approved
- **Rationale:** el owner lo pidió explícitamente y eligió por chat el alcance (táctil + físico). La preferencia es local, persistente, apagada por defecto y no toca el wire.
- **Rollout:** same release

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-002 1.10.0, SPEC-008 0.2.0)
- [x] Dependent documents updated (indexes de CRs)
- [x] Tests added/updated (TEST-089, TEST-090)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [ ] Code change merged (this PR)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Buffy (agent) | Opened; aprobado por el owner el mismo día por chat |
