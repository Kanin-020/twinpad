# Change Request (CR) — CR-138 — Twinpad

| | | |
|---|---|---|
| ID | CR-138 |
| Title | Android: la lista de conexiones guardadas marca la conexión activa con un punto verde |
| Type | `Correction` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-27 |
| Decided | 2026-11-27 |
| Decided by | Owner (reporte en la conversación) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-002 |
| Sections | §1 (vista/listas), §12 (traceability) |
| Current version → new version | `0.11.0 → 0.12.0` |

## 2. Requested change

**Current:**

```text
- `item_saved_device.xml` tiene el punto verde `saved_device_status`, pero
  `SavedDevicesRenderer` solo lo muestra con `isActive && isStreaming()`: la
  sesión vinculada sin transmisión en marcha (o reconectando) no se distingue,
  y el owner ve la lista sin ningún indicador de cuál es la conexión activa.
```

**Proposed (petición del owner):**

```text
- La tarjeta de la conexión guardada cuya sesión está vigente muestra el punto
  verde siempre: U4 (Linked) y U6 (Reconnecting), con o sin transmisión.
- El resto de tarjetas no lo muestra.
- La resolución de la sesión activa se extrae a `activeSavedSessionId(state)`
  (función pura, testeable en JVM) y la lista se repinta con el estado.
```

## 3. Motivation

El owner reporta que en la lista de dispositivos falta el indicador de la
conexión activa. El punto existía, pero atado a que el stream estuviera vivo, de
modo que en una sesión recién vinculada o durante la reconexión no aparecía.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | Sin cambios de flujo | none |
| SPEC-002 | no | Sin cambios de contrato | none |
| ARCH | no | Sin componentes nuevos | none |
| DES-002 | yes | §1 describe el indicador de la conexión activa | update 0.12.0 |
| TEST-0236 | yes | Nuevo test JVM: `activeSavedSessionId` en U4/U6 y sin sesión | añadido |
| Code | yes | `ui/ConnectionTabsController`, `ui/SavedDevicesRenderer`, `ui/PairingActivity`, `item_saved_device.xml` | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Sin cambios de wire | none |

## 5. Options considered

1. Punto verde para la sesión vigente, independiente del stream — **elegida**:
   responde «cuál es la conexión activa» sin depender de la reproducción.
2. Mantener el punto atado al stream (comportamiento actual) — descartada: es la
   causa del reporte.
3. Añadir un texto «Conectada» en la tarjeta — descartada: el owner pide el
   indicador visual, y la tarjeta ya se expande con acciones.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** reporte directo del owner.
- **Rollout:** cambio local de la app; sin cambios de protocolo ni servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.12.0)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests added (TEST-0236 en `ConnectionViewResolverTest`)
- [x] `testing/traceability.md` rows updated (fila TEST-0236 + historial)
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por el reporte del owner |
