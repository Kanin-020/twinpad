# Change Request (CR) — CR-140 — Twinpad

| | | |
|---|---|---|
| ID | CR-140 |
| Title | Android: el panel de conexión deja de mostrar el texto de estado «Vinculado» |
| Type | `Correction` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-27 |
| Decided | 2026-11-27 |
| Decided by | Owner (petición directa) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-002 |
| Sections | §1 (panel de conexión con sesión viva), §12 (traceability) |
| Current version → new version | `0.13.0 → 0.14.0` |

## 2. Requested change

**Current:**

```text
- Con la sesión viva y la vista «Nueva conexión» seleccionada, `renderLinked`
  escribe «Vinculado» en `txt_status` del panel (`pairing_linked`).
```

**Proposed (petición del owner):**

```text
- Ese texto desaparece: el panel de conexión no muestra estado de sesión; la
  sesión vigente se ve en «Sesión actual» (CR-133/CR-135).
- Solo se conservan en `txt_status` los avisos que siguen teniendo sentido ahí:
  «Apuntando al QR…» durante un escaneo con sesión viva y el aviso de enlace
  pendiente (CR-108/CR-134).
- El recurso `pairing_linked` se retira por quedar sin uso.
```

## 3. Motivation

El owner reporta que sobra el texto «Vinculado» en la sesión de conexión; con la
vista de sesión separada (CR-133) ese rótulo ya no aporta y duplica información.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | Sin cambios de flujo | none |
| SPEC-002 | no | Sin cambios de contrato | none |
| ARCH | no | Sin componentes nuevos | none |
| DES-002 | yes | §1: el panel no muestra el estado de sesión | update 0.14.0 |
| TEST-0232/0233/0234/0235 | no | No cambian los resolvers ni el enlace pendiente | none |
| Code | yes | `ui/PairingActivity.renderLinked`, `strings.xml` | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Sin cambios de wire | none |

## 5. Options considered

1. Dejar `txt_status` vacío salvo avisos de escaneo/pendiente — **elegida**: es
   lo que pide el owner y conserva los mensajes útiles.
2. Mantener «Vinculado» solo con la pestaña de conexión visible — descartada: el
   owner pide quitarlo.
3. Mover el rótulo a la cabecera de la vista de sesión — descartada: la sesión
   ya se identifica por su contenido y el estado del stream.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** petición directa del owner.
- **Rollout:** cambio local de UI; sin cambios de protocolo ni servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.14.0)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests: no aplica (texto de UI; sin cambio de comportamiento)
- [x] `testing/traceability.md` historial actualizado
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por petición del owner |
