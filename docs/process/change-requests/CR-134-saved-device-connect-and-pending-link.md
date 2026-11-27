# Change Request (CR) — CR-134 — Twinpad

| | | |
|---|---|---|
| ID | CR-134 |
| Title | Android: «Conectar» en las conexiones guardadas y enlace pedido con sesión viva (también desde la lista) |
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
| Current version → new version | `0.7.0 → 0.8.0` |

## 2. Requested change

**Current:**

```text
CR-108 retiene el QR/código pedido con sesión viva y lo ejecuta al terminar la
sesión. La lista de conexiones guardadas (CR-094/CR-097) solo ofrece un botón
por tarjeta: «Desconectar» en la conexión activa y «Olvidar» en el resto, así
que no hay forma de conectarse a otro dispositivo guardado desde la lista.
PairingViewModel.connectSavedLink existe pero ninguna tarjeta lo invoca; con
sesión viva `resumeFromLink` se rechaza (solo desde Idle) y el toque no haría
nada.
```

**Proposed (petición del owner):**

```text
- Cada tarjeta de conexión guardada inactiva ofrece «Conectar» y «Olvidar»;
  la conexión activa mantiene «Desconectar».
- «Conectar» reanuda la vinculación guardada con `pair_resume` sin QR.
- Si en ese momento hay una sesión viva (U4) —o U6 reconectando—, el enlace
  pedido no se ejecuta ni interrumpe la sesión: se registra como pendiente y
  se ejecuta cuando la sesión termine, igual que el QR de CR-108.
- Mientras la sesión sigue corriendo, el navegador vuelve a «Sesión actual» y
  se avisa con `pairing_pending_link`; el pendiente no espera perpetuamente:
  si la sesión termina en U5 (caída) se vuelve a Idle y el enlace se ejecuta.
- «Conectar» sin sesión viva reanuda de inmediato (paridad con CR-094).
```

## 3. Motivation

El owner pide que la lista de tarjetas permita conectar, no solo olvidar, y que
se pueda enlazar otro dispositivo con la conexión activa sin interrumpir la
sesión. La tarjeta solo pintaba `saved_device_action`; `connectSavedLink` no
tenía disparador en la UI y la reanudación de un guardado exige la máquina en
Idle, por lo que el comportamiento pedido necesita el mismo enlace diferido de
CR-108 extendido a las conexiones guardadas.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | Sin criterios nuevos: sigue el flujo de vinculación/reanudación de CR-094 | none |
| SPEC-002 | no | §6/§14 y el enlace pendiente de CR-108 no cambian; la lista guardada es UI de CR-094/CR-097 | none |
| ARCH | no | Sin nuevos componentes ni cambios de wire | none |
| DES-002 | yes | La lista guardada gana «Conectar» y el pendiente cubre conexiones guardadas | update 0.8.0 |
| TEST-0207 | yes | `PendingLink` pasa a peticiones tipadas (QR / dispositivo guardado) | actualizado |
| TEST-0233 | yes | Nuevo test del pendiente de dispositivo guardado y del «último pedido gana» | añadido |
| Code | yes | `core/PendingLink`, `pairing/PairingViewModel`, `ui/SavedDevicesRenderer`, `ui/PairingActivity`, `item_saved_device.xml`, `strings.xml` | aplicado |
| Data / migrations | no | `PairingLinkStore` no cambia | none |
| Integrations / contracts | no | `pair_resume` ya existe (CR-094) | none |

## 5. Options considered

1. «Conectar» + «Olvidar» en la tarjeta inactiva, con el pendiente de CR-108
   extendido a los guardados — **elegida**: reutiliza la reanudación existente
   y respeta «la sesión activa no se toca».
2. Sustituir «Olvidar» por «Conectar» — descartada: el owner pide «Conectar no
   solo Olvidar»; ambas acciones conviven.
3. Ejecutar «Conectar» cerrando la sesión activa — descartada: interrumpe la
   sesión, justo lo que el owner prohíbe.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pidió explícitamente la acción «Conectar» en las
  tarjetas y que el enlace con sesión viva se registre sin interrumpirla.
- **Rollout:** cambio local de la app; sin cambios de protocolo, datos ni
  servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.8.0, changelog con este CR)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests added (TEST-0233 en `PendingLinkTest`, 5 casos JVM; TEST-0207 actualizado)
- [x] `testing/traceability.md` rows updated (fila TEST-0233 + historial de cambios)
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por petición del owner |
