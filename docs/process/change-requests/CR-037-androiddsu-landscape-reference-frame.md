# Change Request (CR) — CR-037 — Twinpad

| | |
|---|---|
| ID | CR-037 |
| Title | Marco de referencia AndroidDSU landscape exclusivo: accel `(y, -z, x)`, gyro `(-y, -z, x)` |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team (agente) |
| Created | 2026-10-29 |
| Decided | 2026-10-29 |
| Decided by | Product owner (petición expresa en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, DES-002, DES-004, `testing/traceability.md` |
| Sections | SPEC-004 §4 (frase de marco AndroidDSU); DES-002 §1 y snapshot inercial; DES-004 mapeo de muestras inerciales |
| Current version → new version | SPEC-004 `1.9.0 → 1.10.0`; DES-002 `0.3.3 → 0.3.4`; DES-004 `1.4.0 → 1.5.0` |

## 2. Requested change

**Current:**

```text
Android envía el snapshot inercial en el marco AndroidDSU definido por CR-022:
accel = (-x, -z, y), gyro = (x, -z, y). El mapeo está anclado a sostener el
teléfono en portrait, aunque la app Twinpad está bloqueada en landscape
(CR-011, manifiesto screenOrientation="landscape") y su uso es exclusivo
de esa orientación.
```

**Proposed:**

```text
- El marco AndroidDSU de referencia pasa a corresponderse con el grip
  landscape exclusivo de la app (manifiesto screenOrientation="landscape",
  CR-011, ROTATION_90): en landscape el eje X del sensor nativo apunta
  lejos del jugador (arriba del contenido) y el eje Y del sensor nativo
  apunta a la izquierda del jugador.
- Android remapea cada muestra nativa (x, y, z) a:
  accel = (y, -z, x) en m/s²;  gyro = (-y, -z, x) en rad/s.
- Con el teléfono horizontal en reposo (pantalla hacia arriba) ambos
  grips producen la misma lectura DSU (0, -g, 0); no hay salto al
  tomar/colocar el teléfono.
- Cemu sin cambios: solo convierte unidades (gyro ×0.9, accel
  ×9.8066/100) y niega accel Y/Z como su ruta DSU (CR-022). El
  esquema wire `motion` y los monitores no cambian.
- La validación física de los signos en dispositivo fue confirmada por el
  owner (2026-10-29): yaw/pitch/roll correctos en el uso landscape exclusivo.
```

## 3. Motivation

El owner reportó que "el gyro de Android se toma como si el teléfono estuviera en portrait, pero la referencia real debería ser landscape, y debe ser exclusiva de esa orientación porque la app Twinpad solo se usa en landscape". La app ya está bloqueada en landscape desde CR-011; el remapeo wire vigente (CR-022) era el anclado a portrait y produce yaw/pitch/roll cruzados al jugar sosteniendo el teléfono en landscape, que es su único uso.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | El requisito de inputs sin cambio | none |
| SPEC-004 | yes | §4: marco AndroidDSU landscape exclusivo | update (1.10.0) |
| DES-002 | yes | §1 y fila del snapshot inercial: remapeo landscape fijo | update (0.3.4) |
| DES-004 | yes | Fila de mapeo de muestras inerciales cita el marco landscape | update (1.5.0) |
| TEST-083 / TEST-085 | yes | Expectativas de vectores del marco landscape en `ProfileGyroControllerTest` | update descripciones y asserts (sin TEST ID nuevo) |
| Code | yes | `ProfileGyroController.kt` (remapAccelerationVector / remapGyroscopeVector) | hecho en esta iteración |
| Wire | no | Esquema `motion`, timestamp y unidades sin cambios | none |
| Cemu | no | Solo convierte unidades y niega accel Y/Z (CR-022); ruta intacta | none |

## 5. Options considered

1. Rotar el marco actual al grip landscape fijo de la app (accel `(y,-z,x)`, gyro `(-y,-z,x)`) — elegida: mantiene el "una sola fuente de verdad" de CR-022 (el mapeo vive solo en Android) y desactiva la siembra no coherente con el uso real en landscape.
2. Remapeo dependiente de `display.rotation` (como el monitor CR-011) — rechazado: la app no cambia de orientación, por lo que un remapeo dinático añade ramales muertos frente a un mapeo fijo por orientación exclusiva.
3. Compensar en Cemu (nuevos signos/ejes en el adaptador) — rechazado: rompería el principio CR-022 (mapeo único en Android) duplicaría la verdad de los ejes en dos lados; Cemu debe seguir idéntico a su ruta DSU.

## 6. Decision

- **Outcome:** `Approved` (owner-requested)
- **Rationale:** la app Twinpad solo opera en landscape (CR-011), así que el marco de referencia del gyro debe ser el del grip landscape de forma exclusiva y fija.
- **Rollout:** same release; requiere reinstalar el APK Android. Cemu sin recompilar.

## 7. Execution checklist

- [x] Target document(s) updated (version bump y changelog con este CR)
- [x] Dependent documents updated (impact table)
- [x] Tests updated (TEST-083/TEST-085 en `ProfileGyroControllerTest`)
- [x] `testing/traceability.md` rows updated (primera tabla + change history)
- [x] Indexes (`_index.md`) updated
- [x] Code change applied (reempapeo landscape en Android)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-29 | Product owner + Twinpad team | Opened (petición del owner) |
| 1.0.0 | 2026-10-29 | Product owner | Approved: marco AndroidDSU landscape exclusivo `accel (y,-z,x)`, gyro `(-y,-z,x)` |
| 1.1.0 | 2026-10-29 | Twinpad team | Applied: código Android y docs |
| 1.2.0 | 2026-10-29 | Product owner | Validación física de los signos del gyro confirmada en dispositivo |
