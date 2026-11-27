# Change Request (CR) — CR-133 — Twinpad

| | | |
|---|---|---|
| ID | CR-133 |
| Title | Android: «Sesión actual» y «Nueva conexión» son vistas independientes, también sin sesión viva |
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
| Sections | §1 (UI de vinculación y sesión), §12 (traceability) |
| Current version → new version | `0.6.0 → 0.7.0` |

## 2. Requested change

**Current:**

```text
CR-097 separa las vistas en PairingFragment y SessionFragment, pero
ConnectionTabsController.renderSwitch solo muestra SessionFragment con sesión
vinculada: `showSession = sessionLinked && showingSession`. Sin sesión, elegir
«Sesión actual» cae en `showPanel` y pinta el mismo panel de QR/código que
«Nueva conexión»; el menú vuelve a marcar «Nueva conexión».
```

**Proposed:**

```text
La vista elegida en el menú (sesión, nueva conexión o lista guardada) se
resuelve con una función pura `resolveConnectionView` y no depende de que haya
sesión:
- Con sesión viva, «Sesión actual» muestra SessionFragment (video, métricas,
  HUD, controles).
- Sin sesión viva, «Sesión actual» muestra su propio estado vacío
  (`session_empty_container`: título «Sesión actual» y guía a «Nueva conexión»);
  nunca el panel de vinculación.
- Al vincular se pasa a la vista de sesión; al perder la sesión se vuelve al
  panel, donde SPEC-002 §9 muestra el error y «Reintentar».
```

## 3. Motivation

El owner reporta que en la app Android las dos entradas del menú de navegación
(«Nueva conexión» y «Sesión actual») muestran lo mismo al abrirlas. La causa es
la condición `sessionLinked && showingSession` de `ConnectionTabsController`:
sin sesión no existe distinción entre ambas vistas y la selección se revierte a
`nav_new_connection`. Evidencia: `ConnectionTabsController.renderSwitch` y la
rama `showSession = sessionLinked && showingSession`.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | Ningún criterio de aceptación cambia | none |
| SPEC-002 | no | §6/§9 siguen rigiendo estados y mensajes; sin transición nueva | none |
| ARCH | no | Sigue siendo una Activity con dos fragmentos | none |
| DES-002 | yes | La vista de sesión deja de depender de una sesión viva | update 0.7.0 |
| TEST-010..014, TEST-0196 | no | Lógica de vinculación y restauración intactas | none |
| Code | yes | `ConnectionTabsController`, `activity_pairing.xml`, `strings.xml` | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Sin cambios de wire ni de servidor | none |

## 5. Options considered

1. Estado vacío propio de la vista de sesión — **elegida**: mantiene las tres
   entradas del menú siempre utilizables y separa de verdad ambas vistas.
2. Deshabilitar el chip «Sesión actual» sin sesión — descartada: deja la entrada
   del menú sin efecto y no comunica por qué.
3. Do nothing — las dos entradas seguirían mostrando el panel de vinculación.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pidió que cada vista del menú muestre lo suyo; la
  vista de sesión sin sesión muestra un estado vacío propio, y el panel de
  vinculación queda exclusivo de «Nueva conexión».
- **Rollout:** cambio local de la app; sin cambios de protocolo, datos ni
  servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.7.0, changelog con este CR)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests added (TEST-0232 `ConnectionViewResolverTest`, 3 casos JVM)
- [x] `testing/traceability.md` rows updated (fila TEST-0232 + historial de cambios)
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Opened and applied por reporte del owner |
