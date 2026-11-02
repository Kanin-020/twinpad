# Change Request (CR) — CR-058 — Twinpad

| | |
|---|---|
| ID | CR-058 |
| Title | Degradación automática de bitrate por congestión (cierra OQ de SPEC-003 §17) |
| Type | New behavior |
| Status | `Applied (owner-requested)` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | 2026-11-01 |
| Decided by | Owner (usuario) — orden directa: «Puedes implementar 56 + 57 + 58 directo?» |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003, DES-004 |
| Sections | SPEC-003: §8.4 (rewrite), §12 (nota), §17 (OQ cerrada), Changelog · DES-004: realización |
| Current version → new version | SPEC-003 `1.11.0 → 1.12.0` (rebasear al aplicar) · DES-004 `1.14.0 → 1.15.0` |

## 2. Requested change

**Current:**

```text
SPEC-003 v1.1.0 marker: el bitrate es el configurado por el usuario
(video_config → plugin); la degradación solo existe si el usuario baja el
slider y via re-scan CR-027/CR-030 (encoder reinicia + IDR). R5 (§2):
«la degradación la decide siempre el Servidor… el Android nunca la solicita
en v1». §17 deja este Open Question:

«El umbral exacto de degradación automática continúa abierto; no autoriza
transcodificación en el Servidor.» — abierto con esta CR como gatillo.
```

**Proposed:**

```text
§2 — añadir regla:

- **R10 (CR-058):** el Servidor decide la degradación automática de bitrate
  del encoder compartido ante congestión observada en el relay del canal
  media (§4.3). El Android sigue sin solicitar degradación (R5 vigente).

§4.3 — añadir (relay de media):

- Por ventana móvil de 2 000 ms y por sesión cliente, el Servidor cuenta:
  P = AUs publicadas por la fuente destinadas a esa sesión, D = AUs que
  sustituyeron a una AU pendiente por latest-frame-wins sin haber podido
  escribirse al socket (descartes). Sesión congestionada si D/P ≥ 0,25 y
  P ≥ 10 en la ventana. Sale de congestión si D/P < 0,05 durante 10 000 ms
  continuados (histéresis).
- La congestión en el rung de bitrate: la escalera del encoder es
  [bitrate configurado → 1/2 → 1/4 → 1/8 → suelo 500 kbps]. Ante ≥ 2
  ventanas congestionadas, el Servidor baja un rung reenviando video_config
  con el nuevo bitrate_kbps (mismo mecanismo de CR-027/CR-030; resolución
  intacta). Con múltiples sesiones, el rung ejecutado es el mínimo entre
  sesiones congestionadas.
- Recuperación: tras 10 000 ms sin congestión en todas las sesiones, se
  sube un rung (nunca supera el bitrate configurado por el usuario).
  Los saltos requieren IDR: el Servidor reutiliza el gancho post-handshake
  de CR-051 (video_force_idr).

§8.4 — reescribir para describir esta política (el resto, intacto).

§12 — añadir nota: la escalera y los umbrales de R10 son parámetros de
  configuración de Servidor, no parte de contrato wire (no hay cambio de
  mensajes; video_config ya existe).

§17 — eliminar la OQ; queda «Resuelta por CR-058 (R10 §11, degradación automática en el relay §4.3)».

§16 — filas nuevas:

| Aspect | Verified by |
|---|---|
| Política pura de congestión y escalera (ventanas, histéresis, límites) | TEST-0131 |
| Integración: congestión simulada baja rung reenviando video_config + IDR; recuperación sube rung | TEST-0132 |
```

## 3. Motivation

Cuarto cuello de botella del análisis: en Wi-Fi con pérdida, el bitrate
fijo del encoder llena el SNDBUF del relay más rápido de lo que drena la
red; el descarte latest-frame-wins evita el backlog pero no recupera calidad
y el usuario percibe stutter sin ninguna reacción automática. CR-051 ya
solucionó el backlog instantáneo, pero no reduce el bitrate: con pérdida
sostenida, el stutter persiste. R5 ya marca al Servidor como único
decididor; esta CR concreta la política que §17 reconoce abierta desde
SPEC-003 v1.1.0.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-005 | no | «siempre visual» está intacto; esto solo cambia bitrate ante congestión, alineado con la expectativa de estabilidad del owner | none |
| SPEC-002 | no | — | none |
| SPEC-003 | yes | §2 R10, §4.3 política, §8.4 rewrite, §17 OQ cerrada | update (1.12.0) |
| SPEC-006 | no | video_config ya existe como línea server→plugin; sin cambio | none |
| ARCH | no | la política vive en el Servidor (decisor ya designado por R5) | none |
| DES-004 | yes | realización: observador de descartes en `server/src/media.rs` + sistema de ladder | update (1.15.0) |
| TEST-0126/0127 | yes | reservados: 0126 core (política pura), 0127 server integración | add rows al aplicar |
| Code | yes | `server/src/media.rs` (contadores D/P) + servidor (reenvío video_config) + integración plugin existente | al implementar |
| Data / migrations | no | — | none |
| Integrations / contracts | no | sin cambio wire; el plugin ya acepta video_config dinámico (CR-027/CR-030) y video_force_idr (CR-051) | none |

## 5. Options considered

1. **Ladder con ventana D/P + histéresis (propuesto)** — usa señales ya
   disponibles en el relay (descartes latest-frame-wins), no toca wire y
   respeta R5.
2. Adaptar por RTT/loss en el socket media — requiere cambios en SPEC-003
   §4.3 (métricas nuevas por socket), más invasivo; sin evidencia de que el
   descarte no sea el mejor predictor en el punto donde ya se decide.
3. Do nothing — el Open Question de §17 sigue sin respuesta y el stutter
   ante pérdida sostenida no tiene remedio automático.

## 6. Decision

- **Outcome:** Applied (owner-requested) — umbrales 0,25/0,05 y escalera
  1/2-1/4-1/8 con suelo 500 kbps aplicados tal como se propuso.
- **Rationale:** cierra una OQ abierta y armoniza con R5; no requiere
  cambios de wire ni de plugin (infra de CR-051 ya despliega IDR on demand).
- **Rollout:** mismo release; Servidor actualizado, teléfono sin cambios,
  plugin sin cambios.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-003 1.13.0)
- [x] Dependent documents updated (DES-004)
- [x] Tests added/updated (TEST-0131 core, TEST-0132 server — renumerados)
- [x] `testing/traceability.md` updated
- [x] Indexes updated
- [x] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened en lote de cuellos de botella de conexión |
| 1.0.0 | 2026-11-01 | Buffy (agente) | Applied (owner-requested): R10 implementado en core::bitrate + server::media; TEST-0131/0132 |
