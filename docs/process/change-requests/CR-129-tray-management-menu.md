# Change Request (CR) — CR-129 — Twinpad

| | |
|---|---|
| ID | CR-129 |
| Title | Simplificar las opciones del menú del tray |
| Type | Behavior change |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-010 §4/§5 W1/W3; DES-005 §1/§6 |
| Sections | Eliminar el ítem «¿Dónde está el URI?» y el icono de «Abrir interfaz de gestión» |
| Current version → new version | SPEC-010 0.5.0 → 0.6.0; DES-005 0.4.1 → 0.5.0 |

## 2. Requested change

**Current:** el menú del tray muestra «Abrir interfaz de gestión» con el icono
`web-browser` y una opción «¿Dónde está el URI?» que imprime un recordatorio
por stdout.

**Proposed:** mantener «Abrir interfaz de gestión» sin icono y eliminar por
completo la opción «¿Dónde está el URI?» del menú. La página de gestión y la
salida de arranque del servidor conservan sus contenidos; solo cambia el menú
del tray.

## 3. Motivation

El owner solicita quitar el icono de la opción para abrir la interfaz de gestión
y eliminar la opción del menú que indica dónde está la URI.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-010 | Yes | El menú deja de ofrecer el recordatorio de URI; abrir interfaz queda sin icono | Actualizar §4, W1/W3 y changelog |
| DES-005 | Yes | Descripción y flujo del menú deben coincidir con su implementación | Actualizar §1, §6 y changelog |
| REQ-001 / ARCH | No | No cambia la capacidad de gestión ni sus límites | None |
| Tests | No | No se añade comportamiento fuera de la composición del menú | Ninguno nuevo |
| Code | Yes | Retirar el icono y el ítem URI de `server/src/tray.rs` | Aplicar el cambio |
| Integrations / contracts | No | HTTP, URI de Pairing y salida de arranque permanecen iguales | None |

## 5. Options considered

1. Simplificar solo el menú como solicita el owner — elimina las dos opciones
   visuales sin alterar la página ni los mecanismos de pairing.
2. Eliminar también la página o la salida de URI — descartado; excede la petición.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** retirar exclusivamente el icono y la opción de ayuda sobre la
  URI del menú del tray, preservando las demás opciones y la interfaz local.
- **Rollout:** misma release; no hay migración.

## 7. Execution checklist

- [ ] SPEC-010 y DES-005 actualizados con versión y changelog
- [ ] `server/src/tray.rs` actualizado
- [ ] CR index actualizado
- [ ] Verificación enfocada ejecutada

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado por petición del owner |
