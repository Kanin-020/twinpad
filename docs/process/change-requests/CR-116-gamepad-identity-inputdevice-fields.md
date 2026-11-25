# Change Request (CR) — CR-116 — Twinpad

| | |
|---|---|
| ID | CR-116 |
| Title | Identidad de modelo del mando con los campos reales de `InputDevice` (`vendorId`/`productId`/`name`) |
| Type | Technical correction |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat: aprobación de SPEC-011 e implementación) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-011 §3 |
| Sections | §3 Definitions (identidad de modelo) |
| Current version → new version | `0.2.0 → 0.2.1` |

## 2. Requested change

**Current (SPEC-011 §3):**

```text
Identidad de modelo: manufacturer + product del InputDevice; si alguno
falta, se usa descriptor.
```

**Proposed:**

```text
Identidad de modelo: vendorId + productId + name del InputDevice; si los
ids son 0 y no hay nombre, se usa descriptor.
```

## 3. Motivation

`android.view.InputDevice` no expone `getManufacturer()` ni `getProduct()`
(esos son campos de `BluetoothDevice`). Los datos estables disponibles para
un mando son `getVendorId()`, `getProductId()`, `getName()` y
`getDescriptor()`. La decisión del owner (perfiles por fabricante y modelo,
no por unidad física) no cambia: dos mandos del mismo vendor/product/name
siguen compartiendo perfiles.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-011 | yes | §3 precisa de dónde sale la identidad | update vía este CR |
| REQ-003 | no | Sin cambio de comportamiento | none |
| Code | yes | `gamepadModelKey` usa vendor/product/name/descriptor | implementado |
| Tests | yes | TEST-0209 cubre la clave de modelo con esos campos | actualizado |

## 5. Decision

- **Outcome:** corrección técnica aceptada al aprobar la implementación de SPEC-011; la identidad sigue siendo por fabricante y modelo, no por unidad física.
- **Rollout:** cambio local de la app; sin wire ni servidor.

## 6. Execution checklist

- [x] Code applied (`hid/GamepadMapping.kt` `gamepadModelKey`)
- [x] Test updated (TEST-0209)
- [x] SPEC-011 updated (0.2.1, changelog referencia este CR)
- [x] Index updated (`docs/specs/_index.md`)
- [x] Traceability row updated (TEST-0209)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Open y decisión (corrección técnica al aprobar SPEC-011) |
