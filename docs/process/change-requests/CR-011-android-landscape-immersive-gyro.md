# Change Request (CR) — CR-011 — Twinpad

| | |
|---|---|
| ID | CR-011 |
| Title | Bloqueo landscape inmersivo y ejes gyro relativos a pantalla |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (solicitud explícita en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §5/§11, SPEC-004 §4, DES-002 §1/§12, `testing/traceability.md` |
| Sections | Orientación/pantalla de la app; marco de referencia de orientación absoluta gyro |
| Current version → new version | SPEC-002 `1.1.0 → 1.2.0`; SPEC-004 `1.2.0 → 1.3.0`; DES-002 `0.1.0 → 0.2.0` |

## 2. Requested change

**Current:**

```text
SPEC-002 §5/§11 no fija orientación ni visibilidad de las barras del sistema.
SPEC-004 §4 define `gyro` como yaw/pitch/roll absolutos int16 sin especificar
el marco de coordenadas de esos ángulos.
```

**Proposed:**

```text
SPEC-002: PairingActivity siempre se presenta en landscape habitual fijo,
sin orientación vertical ni rotación automática; oculta las barras de estado
y navegación mientras está visible.
SPEC-004: yaw/pitch/roll se expresan en el marco de coordenadas de la pantalla;
la app remapea la matriz del sensor al giro actual de la pantalla antes de
calcular y enviar los ángulos. El esquema y el orden de los tres valores wire
no cambian.
```

## 3. Motivation

El owner reportó que la app debe quedar horizontal sin rotaciones, sin barras
superior/lateral y que los ejes observados por el juego intercambian orientación
al girar el teléfono. El bloqueo de orientación y el remapeo explícito al marco
de la pantalla eliminan respectivamente el cambio visual y el desfase portrait/
landscape; la conversión conserva el contrato wire de SPEC-004.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 / REQ-003 | no | Las REQ no fijan orientación de UI ni el marco de coordenadas del gyro | none |
| SPEC-002 | yes | Comportamiento de orientación y barras | actualizar §5/§11, versión y changelog |
| SPEC-004 | yes | Marco de yaw/pitch/roll precisado; wire sin cambios | actualizar §4, versión y changelog |
| ARCH | no | Sin cambios de componentes ni límites | none |
| DES-002 | yes | Documentar configuración de Activity, insets y remapeo del sensor | actualizar §1/§12 y changelog |
| TEST-059 | yes | Prueba Android de orientación, barras y ejes | añadir test y fila de traceability |
| Code | yes | Manifest, Activity y mapper de coordenadas | implementación Android |
| Data / migrations | no | Sin persistencia | none |
| Integrations / contracts | no | NDJSON y campos wire permanecen idénticos | none |

## 5. Options considered

1. Fijar la app en landscape habitual, ocultar system bars y remapear el sensor al marco de pantalla — coincide con el resultado solicitado y no cambia el protocolo.
2. Do nothing — conserva verticalidad/barras y los ejes percibidos como intercambiados.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el Product owner pidió explícitamente landscape fijo, pantalla completa y corregir el desfase del gyro; eligió landscape habitual (no invertido). Se acepta que Android puede mostrar temporalmente barras del sistema por un gesto del usuario, según el comportamiento de immersive mode del SO.
- **Rollout:** misma iteración; no requiere migración.

## 7. Execution checklist

- [x] SPEC-002/SPEC-004 actualizados conforme a la decisión.
- [x] DES-002 actualizado.
- [x] TEST-059 añadido y registrado en `testing/traceability.md`.
- [x] Índices actualizados.
- [x] Implementación Android y verificación instrumentada/unitarias/lint completadas.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-18 | Product owner + Twinpad team | Solicitud explícita aprobada; landscape habitual, inmersivo y gyro relativo a pantalla |
