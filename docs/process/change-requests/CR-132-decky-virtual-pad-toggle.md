# Change Request (CR) — CR-132 — Twinpad

| | | |
|---|---|---|
| ID | CR-132 |
| Title | El panel Decky enciende y apaga el mando virtual con la misma superficie local que la página y el tray |
| Type | New behavior |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner (petición en chat) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-010 |
| Sections | §4, §5 W10, §9 AC-7 |
| Current version → new version | SPEC-010 0.6.0 → 0.7.0 |

## 2. Requested change

**Current:** W10 ofrece el interruptor del mando virtual DualSense en la página
local y en el menú del tray. El panel de Decky no lo muestra, así que desde el
QAM de Steam no se puede activar ni desactivar.

**Proposed:** el panel de Decky añade un `ToggleField` «Mando virtual
(DualSense)» en su sección «Mando virtual». No abre un canal nuevo:

- Lee el estado vigente del mismo HTML de `GET /` que ya usa para W2, anclando
  `id="pad-state"` (`Activado`/`Desactivado`). Si el ancla no aparece, el
  interruptor queda deshabilitado en vez de inventar un estado.
- Conmuta con `POST /virtual-pad`, cuerpo `enabled=1|0` y cabecera
  `X-Twinpad-Action` (S1), exactamente el contrato de W10.
- Un 500 de E9 muestra el motivo devuelto por el servidor en un toast y el
  siguiente refresco conserva el estado real; el servidor sigue vivo.

Texto normativo añadido a SPEC-010 §5 W10:

```text
  El panel de Decky (QAM) ofrece el mismo interruptor «Mando virtual
  (DualSense)»: lee el estado de `GET /` (ancla `id="pad-state"`) y conmuta con
  el mismo `POST /virtual-pad` y cabecera S1. Si no puede leer el estado, el
  interruptor queda deshabilitado; si el servidor responde 500 (E9), muestra el
  motivo y no cambia el estado local por su cuenta.
```

## 3. Motivation

El owner pidió añadir al plugin de Decky el botón para activar y desactivar el
mando virtual. La página y el tray ya comparten `POST /virtual-pad`; el panel
solo necesita presentar ese mismo contrato y reflejar el estado que ya publica
`GET /` (SPEC-010 W2/W10).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-001 | no | El panel sigue operando la interfaz local existente | none |
| SPEC-010 | yes | W10 gana el panel Decky como tercera superficie del mismo interruptor; §4 y AC-7 lo reflejan | update |
| ARCH | no | Sin canal ni componente nuevo; el plugin sigue siendo un cliente loopback | none |
| DES-005 | no | La realización de la página y el tray no cambia | none |
| TEST-0231 | yes | Prueba Python del parseo de estado y del POST con cabecera/errores | add |
| Code | yes | `integrations/decky-twinpad/main.py`, `src/index.tsx`, README | small |
| Data / migrations | no | Sin persistencia nueva: la preferencia sigue en el servidor | none |
| Integrations / contracts | no | Reutiliza `GET /` y `POST /virtual-pad`; no hay contrato nuevo | none |

## 5. Options considered

1. **Reutilizar `POST /virtual-pad` y el ancla de `GET /` (elegida):** cero
   contrato nuevo, mismo estado y misma preferencia que página y tray.
2. Añadir un `GET /status` JSON para el panel — descartada por ahora: es
   contrato nuevo y no hace falta para un único booleano ya visible en W2.
3. No hacer nada — el QAM seguiría sin poder operar el mando virtual.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pide el control en Decky; la superficie local ya
  contratada cubre la operación completa y evita duplicar estado.
- **Rollout:** misma release; sin migración. Sin servidor activo el interruptor
  se muestra deshabilitado.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-010 0.7.0)
- [x] Dependent documents updated (índice de CR)
- [x] Tests added/updated (TEST-0231)
- [x] `testing/traceability.md` rows updated
- [x] Indexes (`_index.md`) updated
- [x] Code: backend y panel Decky, README

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado por la petición del owner; el panel Decky reutiliza `POST /virtual-pad` y el estado de `GET /` |
