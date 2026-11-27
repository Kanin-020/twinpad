# Change Request (CR) — CR-135 — Twinpad

| | | |
|---|---|---|
| ID | CR-135 |
| Title | Android: la reconexión con la PC (loader) se muestra en la vista de sesión, no en el panel «Nueva conexión» |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-27 |
| Decided | 2026-11-27 |
| Decided by | Owner (petición en la conversación) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-002 |
| Sections | §1 (UI de vinculación, sesión y conexiones guardadas), §12 (traceability) |
| Current version → new version | `0.8.0 → 0.9.0` |

## 2. Requested change

**Current:**

```text
En U6 (Reconectando) el panel de «Nueva conexión» pinta el loader
(`progress_pairing`), el aviso «Reconectando con la PC», «Cancelar» y
«Desconectar», junto a los botones de vinculación. La vista de sesión solo
existe en U4 (`sessionLinked = state is Linked`), así que la reconexión —que
pertenece a la sesión retenida— se ve en la ventana de conectar.
```

**Proposed (petición del owner):**

```text
- U6 cuenta como sesión viva para la navegación: la vista de sesión muestra un
  estado de reconexión propio (`session_reconnect_container`: loader,
  «Reconectando con la PC» y «Cancelar»).
- El panel «Nueva conexión» ya no pinta loader, aviso, «Cancelar» ni
  «Desconectar» de la reconexión; sin acciones porque en U6 no se escanea.
- El contenedor del video se mantiene visible con la sesión retenida para no
  destruir la Surface (CR-127); el loader se pinta encima.
- Al abrir la app con una vinculación guardada se entra en «Sesión actual» con
  el loader. Si la ventana de retención vence (U5), se vuelve al panel con el
  error y «Reintentar» (SPEC-002 §9).
- «Cancelar» en la vista de sesión detiene el reintento (U6 → U1) sin borrar la
  vinculación guardada; «Desconectar dispositivo» sigue en la fila de la
  conexión activa (CR-094).
```

## 3. Motivation

El owner reporta que en la ventana principal de conectar aparece el loader de
«Reconectando con la PC» junto a «Escanear QR», «Cancelar» y «Reintentar», y que
ese estado corresponde a la vista de sesión actual. La causa es que `renderState`
pinta U6 en el panel y la navegación no considera U6 como sesión.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | El flujo de reanudación (CR-056/CR-094) no cambia | none |
| SPEC-002 | no | §6/§9 y la transición U6 → U1/U5 no cambian; solo cambia dónde se pinta | none |
| ARCH | no | Sin cambios de componentes ni de red | none |
| DES-002 | yes | La vista de sesión gana su estado de reconexión | update 0.9.0 |
| TEST-010..014, TEST-0128..0130 | no | Lógica de reconexión intacta | none |
| TEST-0234 | yes | Nuevo test de `resolveSessionPane` (vista de sesión vs panel) | añadido |
| Code | yes | `ui/ConnectionTabsController`, `ui/PairingActivity`, `activity_pairing.xml`, `fragment_pairing.xml`, `strings.xml` | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Sin cambios de wire ni de servidor | none |

## 5. Options considered

1. Estado de reconexión propio de la vista de sesión, encima del video —
   **elegida**: la reconexión pertenece a la sesión y conserva la Surface.
2. Dejar el loader en el panel y además duplicarlo en la sesión — descartada:
   el owner pide que ya no esté en la ventana de conectar.
3. Ocultar el panel durante U6 sin mostrar nada — descartada: el usuario
   perdería el aviso de qué está pasando y la acción de cancelar.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pidió expresamente que el loader de reconexión vaya a
  la vista de sesión actual y no a la ventana de conectar.
- **Rollout:** cambio local de la app; sin cambios de protocolo, datos ni
  servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.9.0, changelog con este CR)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests added (TEST-0234 en `ConnectionViewResolverTest`, 3 casos JVM)
- [x] `testing/traceability.md` rows updated (fila TEST-0234 + historial de cambios)
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por petición del owner |
