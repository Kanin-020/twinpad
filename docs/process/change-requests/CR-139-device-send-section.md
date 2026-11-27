# Change Request (CR) — CR-139 — Twinpad

| | | |
|---|---|---|
| ID | CR-139 |
| Title | Android: micrófono, cámara y NFC comparten un solo apartado «Envío de datos del dispositivo» |
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
| Sections | §1 (drawer de ajustes), §12 (traceability) |
| Current version → new version | `0.12.0 → 0.13.0` |

## 2. Requested change

**Current:**

```text
- El drawer abre dos apartados con título propio, «Micrófono del teléfono» y
  «Cámara del teléfono»; el interruptor NFC (CR-137) queda suelto bajo el
  selector de lente de la cámara, sin apartado común.
```

**Proposed (petición del owner):**

```text
- Un único apartado «Envío de datos del dispositivo» con los tres envíos:
  «Enviar micrófono al GamePad», «Enviar cámara al GamePad» (+ lente) y
  «Activar envío NFC».
- Los títulos «Micrófono del teléfono» y «Cámara del teléfono» desaparecen; los
  textos de los interruptores ya identifican cada envío.
- Sin cambios de comportamiento ni de persistencia: los tres interruptores
  siguen gobernando su envío como hasta ahora (CR-035, CR-034, CR-137).
```

## 3. Motivation

El owner pide que los tres envíos del dispositivo aparezcan juntos en un mismo
apartado, en vez de repartirse entre secciones y dejar el NFC como interruptor
suelto.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | Sin cambios de flujo | none |
| SPEC-002 | no | Sin cambios de contrato (R4: textos en recursos) | none |
| ARCH | no | Sin componentes nuevos | none |
| DES-002 | yes | §1 describe el apartado único del drawer | update 0.13.0 |
| TEST-069 | no | La persistencia y los defaults no cambian | none |
| Code | yes | `drawer_settings.xml`, `strings.xml` | aplicado |
| Data / migrations | no | Claves de preferencias intactas | none |
| Integrations / contracts | no | Sin cambios de wire | none |

## 5. Options considered

1. Un apartado con los tres interruptores — **elegida**: es lo que pide el
   owner y mantiene los controles en una sola lectura.
2. Mantener los títulos por dispositivo y añadir el NFC al de cámara —
   descartada: seguiría sin haber un apartado común.
3. Subapartados «Audio / Vídeo / NFC» dentro de «Envío de datos» — descartada:
   añade ruido para tres controles.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** petición directa del owner.
- **Rollout:** cambio local de UI; sin cambios de protocolo, datos ni servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.13.0)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests: no aplica (sin cambio de comportamiento; TEST-069 cubre ajustes)
- [x] `testing/traceability.md` historial actualizado
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por petición del owner |
