# Change Request (CR) — CR-097 — Twinpad

| | |
|---|---|
| ID | CR-097 |
| Title | Android: separar la vista de conexión y la vista de sesión en fragmentos dentro de PairingActivity |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-11 |
| Decided | 2026-11-11 |
| Decided by | Owner (opción 1 elegida en la conversación) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | ARCH (módulo Android, UI de vinculación) |
| Sections | Componentes de la app Android: pantalla de vinculación y sesión |
| Current version → new version | pendiente de decisión (se fija al aplicar) |

## 2. Requested change

**Current:**

```text
Una sola Activity (PairingActivity). La vista de conexión (título, Escanear,
Desconectar, Reintentar, preview de cámara) y la vista de sesión (video,
medidores, metrics_slider) viven superpuestas en activity_pairing.xml y se
alternan con visibility desde applyUiState.
```

**Proposed:**

```text
Se mantiene una sola Activity (PairingActivity) que hospeda dos fragmentos:
- PairingFragment: vista de conexión (Idle, Scanning, Handshaking, Reconnecting, Failure).
- SessionFragment: vista de sesión (Linked: video, medidores, metrics_slider).
PairingActivity elige el fragmento según PairingUiState; ninguna vista de
conexión y de sesión comparte pantalla. El drawer de ajustes, el servicio en
primer plano y el ciclo de la cámara siguen en la Activity.
```

## 3. Motivation

La vista de sesión y la de conexión se mezclan en el mismo layout. Las
visibilidades se reparten entre muchas líneas de `applyUiState`, y un elemento
puede quedar visible en el estado equivocado (botones de conexión durante el
stream, medidores detrás del video). El owner pidió separar las vistas.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ | no | El comportamiento visible no cambia | none |
| SPEC | no | Ninguna regla de estado cambia | none |
| ARCH | yes | Describe la pantalla de vinculación como una vista | update |
| TEST | yes | Tests de vista (si los hay) deben apuntar al fragmento | update |
| Code | yes | `activity_pairing.xml`, `PairingActivity`, `ProfileTouchController` (búsqueda de `video_surface_container`), `MetricsSliderController`, `WaitingGaugePanel` | PR |
| Data / migrations | no | - | none |
| Integrations / contracts | no | - | none |

## 5. Options considered

1. Una Activity con fragmentos `PairingFragment` y `SessionFragment` — **elegida**.
2. `StreamActivity` aparte para la sesión — descartada: obliga a pasar el estado entre actividades.
3. Do nothing — los solapamientos seguirán apareciendo.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner eligió la opción 1: una Activity con dos fragmentos, sin cambiar el comportamiento visible.
- **Rollout:** misma release. Sin cambios de protocolo ni de datos.

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [ ] Indexes (`_index.md`) updated
- [ ] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-11 | Buffy (agente) | Opened |
