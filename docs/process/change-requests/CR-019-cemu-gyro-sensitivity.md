# Change Request (CR) — CR-019 — Twinpad

| | |
|---|---|
| ID | CR-019 |
| Title | Reducir al 50% la sensibilidad gyro de Cemu en VPAD |
| Type | Behavior change |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-22 |
| Decided | 2026-10-22 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, DES-004, testing/traceability.md |
| Sections | SPEC-004 §4/§10/§16; DES-004 §10/§12; TEST-081 |
| Current version → new version | SPEC-004 1.5.1 → 1.5.2; DES-004 1.1.4 → 1.1.5 |

## 2. Requested change

**Current (SPEC-004 §4):**

```text
sticks, D-pad, orientación gyro y touch se mapean directamente.
```

**Approved:**

```text
En el adaptador Cemu, cada ángulo absoluto yaw/pitch/roll recibido se multiplica por 0.5 antes del mapeo al VPAD. El reporte wire, los valores mostrados por ambos monitores y los demás campos no cambian.
```

## 3. Motivation

El usuario pide reducir la sensibilidad del gyro aplicada por Cemu al juego y elige explícitamente una ganancia fija 0.5× (la mitad). La reducción se limita a la salida VPAD; los valores absolutos recibidos y el diagnóstico visual permanecen fieles al reporte.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | No | No cambia la capacidad requerida | None |
| SPEC-004 | Yes | Cambia la ganancia del gyro solo en el adaptador Cemu→VPAD | Actualizar §4/§10/§16 mediante CR aprobado |
| ARCH | No | Se conserva la frontera existente | None |
| DES-004 | Yes | Aplicar el factor 0.5 antes de convertir ejes a vueltas VPAD | Actualizar diseño |
| TEST-081 | Yes | Verificar escala 0.5×, signos, ejes y cero | Añadir y registrar |
| Code | Yes | La conversión Cemu→VPAD aplica ganancia fija; monitor sin cambios | Implementar |
| Data / migrations | No | Sin persistencia ni migración | None |
| Integrations / contracts | No | No cambia el formato wire ni el SDK | None |

## 5. Options considered

1. Aplicar 0.5× fijo solo al gyro Cemu→VPAD — seleccionado por el usuario; reduce a la mitad la respuesta sin alterar el reporte ni su diagnóstico.
2. Do nothing — mantiene la sensibilidad actual que el usuario considera alta.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** El usuario eligió explícitamente reducir a la mitad la sensibilidad del gyro aplicada al VPAD en Cemu. Limitar la ganancia a ese adaptador preserva el input absoluto wire y la equivalencia visual Android/Cemu.
- **Rollout:** Misma iteración; sin cambio wire ni datos persistidos.

## 7. Execution checklist

> El efecto sobre orientación absoluta documentado por este CR fue supersedido por CR-021 el 2026-10-24; el historial y la decisión aprobada se conservan.

- [x] Owner aprueba la ganancia fija 0.5× en Cemu/VPAD.
- [x] SPEC-004 y DES-004 actualizados con versión y changelog CR-019.
- [x] TEST-081 añadido y registrado en trazabilidad.
- [x] Índices y change history actualizados.
- [ ] Build Cemu y verificaciones de estilo completados.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-22 | Product owner + Twinpad team | Abierto y aprobado el cambio de sensibilidad gyro de Cemu |
