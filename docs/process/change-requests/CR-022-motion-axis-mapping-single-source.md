# Change Request (CR) — CR-022 — Twinpad

| | |
|---|---|
| ID | CR-022 |
| Title | Un único mapeo de ejes AndroidDSU (en Android) y ruta Cemu idéntica a DSU |
| Type | Correction |
| Status | `Applied` |
| Author | Twinpad team (agent + owner) |
| Created | 2026-10-26 |
| Decided | 2026-10-26 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, DES-002, DES-004, `testing/traceability.md` |
| Sections | SPEC-004 §4 (Android → snapshot inercial, Cemu adaptation sentence); DES-002 snapshot inercial; DES-004 mapeo de muestras inerciales |
| Current version → new version | SPEC-004 1.6.0 → 1.7.0; DES-002 0.3.2 → 0.3.3; DES-004 1.3.0 → 1.4.0 |

## 2. Requested change

**Current:**

```text
Cemu adapta unidades, signos y ejes de la muestra Twinpad (gyro ×0.9 con remapeo de ejes;
aceleración ×9.8066/100 con remapeo X/Y/Z) antes de alimentar WiiUMotionHandler.
```

**Proposed:**

```text
Android envía el snapshot inercial ya en el marco de referencia AndroidDSU:
  accel = (-x, -z, y) en m/s²,  gyro = (x, -z, y) en rad/s.
Cemu solo convierte unidades: gyro ×0.9 (rad/s), accel ×9.8066/100 (sin cambiar ejes).
Al alimentar WiiUMotionHandler, Cemu aplica el mismo tratamiento de signos que su
ruta DSU: accel Y y Z negadas; gyro sin cambios. La ruta Twinpad queda idéntica a la DSU.
```

## 3. Motivation

Tras CR-021, el mapeo de ejes quedó duplicado: Android y Cemu lo aplicaban ambos, y Cemu no lo aplicaba igual que su ruta DSU (aceleración sin negar Y/Z en VPAD). Esto provocaba orientación incorrecta del contenido al girar el teléfono en landscape. Se verificó contra `reference/AndroidDSU` (`MainActivity.java` líneas 329-345) y contra `DSUControllerProvider.cpp` (líneas 422-431). Decisión del owner: el mapeo vive en Android y el camino Twinpad debe ser idéntico al DSU.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | Requisito de inputs sin cambio | none |
| SPEC-004 | yes | Texto de adaptación Cemu y mapeo de Android | update (1.7.0) |
| DES-002 | yes | Snapshot inercial Android con mapeo de referencia | update (0.3.3) |
| DES-004 | yes | Tabla de mapeo de muestras inerciales | update (1.4.0) |
| TEST-083 / TEST-084 | yes | Nuevo test de vectores Android; test Cemu de unidades | add TEST-085 row; update descriptions |
| Code | yes | `ProfileGyroController.kt`, `PairingActivity.kt`, `TwinpadClient.h`, `VPADController.cpp`, tests | done |
| Wire | no | Esquema `motion` sin cambios | none |

## 5. Options considered

1. Mapeo solo en Android, Cemu solo escala y niega accel Y/Z como DSU — elegido: una sola fuente de verdad, igual que la referencia.
2. Mantener remapeo duplicado en Android y Cemu — rechazado: causa el error actual.
3. Mapeo solo en Cemu — rechazado: el owner eligió Android.

## 6. Decision

- **Outcome:** Approved
- **Rationale:** Decisión del owner: mapeo en Android y ruta Cemu idéntica a DSU.
- **Rollout:** same release; requiere reinstalar el APK Android y recompilar Cemu.

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST IDs)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-26 | Twinpad team | Opened and applied |
