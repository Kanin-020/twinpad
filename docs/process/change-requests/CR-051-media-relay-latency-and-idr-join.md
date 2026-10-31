# Change Request (CR) — CR-051 — Twinpad

| | |
|---|---|
| ID | CR-051 |
| Title | Latencia del canal media: SNDBUF/TCP_NODELAY en el relay binario, despertar por Condvar en el MediaHub e IDR inmediata al (re)conectar (SPEC-003 §8.2) |
| Type | Behavior change |
| Status | Applied |
| Author | Buffy (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Product owner (solicitud directa del owner) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 |
| Sections | §7.4 (nuevo), §12 (fila), Changelog |
| Current version → new version | `1.13.0 → 1.14.0` |

## 2. Requested change

**Current:**

```text
- El socket TCP del canal media (SPEC-003 §4.2, puerto 8425) se usa tal como
  lo acepta el listener: sin TCP_NODELAY ni SNDBUF acotado. Bajo Wi-Fi que
  drena menos que el bitrate, el SNDBUF por defecto (256 KB–4 MB) se llena
  con video viejo (el mismo síntoma anti-lag que ya se corrigió en la sesión
  NDJSON de main.rs con StreamSink::prepare_for_streaming).
- El relay del hub sondea: cada cliente media consulta `latest_for()` en un
  bucle con `sleep` de 1–2 ms; una AU publicada entre la consulta y el
  siguiente poll añade hasta ~2 ms y varianza de llegada.
- «El servidor solicita IDR para el nuevo suscriptor» (SPEC-003 §8.2) no
  tiene mecanismo: el teléfono (re)conectado espera hasta 1 s de cadencia
  periódica del encoder antes de su primer AU (o de una recuperación tras
  pérdida de paquetes).
```

**Proposed:**

```text
- `server::media` ajusta el socket de cada cliente media con TCP_NODELAY y
  SNDBUF 4 MiB (misma técnica ya probada en la sesión NDJSON).
- `MediaHub` publica con `notify_all` (Mutex+Condvar); el relay espera una
  AU forwardable (IDR si es la primera, `fn`/`ts` mayores después) bajo la
  exclusión del propio lock del hub, sin sondeo activo para consumidores
  atareados (timeout tope 50 ms re-evalúa selección/estado externo).
- Al autenticar cada cliente media, el Servidor ejecuta el gancho post-
  handshake que envía `{"type":"video_force_idr"}` al plugin Cemu vivo; el
  adaptador marca `m_forceNextIdr` (ahora atómica) para publicar IDR con
  SPS/PPS en el frame siguiente.
```

## 3. Motivation

Implementación de la revisión de cuellos de botella solicitada por el owner
tras cerrar CR-048/CR-049: el HUD mostraba fps/bitrate correctos pero el
tintineo persistía bajo Wi-Fi saturada (SNDBUF del relay jamás sufrió la
corrección anti-lag del canal NDJSON), la varianza del poll de 1–2 ms la
compensaba después el EMA de CR-048, y el re-join del teléfono tardaba ~1 s
en pintar porque SPEC-003 §8.2 («solicita IDR para el nuevo suscriptor»)
estaba en el SPEC sin mecanismo de implementación.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | no | REQ intacto | none |
| SPEC-003 | no | §8.2 ya manda la solicitud de IDR; esta CR la implementa, no cambia texto wire | none |
| SPEC-006 | no | la línea nueva server→plugin reutiliza el IPC primario ya definido | none |
| ARCH | no | sin cambio de fronteras | none |
| DES-004 | yes | §7.4, §12, changelog 1.14.0; `video_force_idr` server→Cemu y m_forceNextIdr atómica | update |
| TEST-0113 (nuevo) | yes | test Rust del enrutamiento `video_force_idr` | add row |
| TEST-072 | yes (extensión) | el test loopback del relay afirma ahora el disparo del gancho post-handshake | update notes |
| Code | yes | `server/src/media.rs`, `server/src/main.rs`, `server/src/games.rs`, `TwinpadClient.{h,cpp}` | done esta iteración |
| Data / migrations | no | — | none |
| Integrations / contracts | no | sin cambio de wire SPEC-003; `video_force_idr` es línea interna server→plugin | none |

## 5. Options considered

1. Lote propuesto (3 ajustes sin cambio de wire en el tramo cifrado) — elimina el
   bloat del socket, la varianza del poll y los ~1 s de espera de IDR.
2. Do nothing — el tintineo bajo Wi-Fi saturada y el re-join lento
   persisten; el sondeo de 1–2 ms sigue añadiendo varianza que el EMA
   de CR-048 debe absorber.
3. Reescribir el relay completo en async/broadcast — grande y sin ganancia
   medible frente al Condvar sobre el mismo lock.

## 6. Decision

- **Outcome:** Approved (owner: «Implementa 1 + 2 + 4»).
- **Rationale:** los tres ajustes implementan requisitos ya publicados (lag
  anti-bloat NDJSON, §8.2) con el mismo patrón probado, sin tocar wire.
- **Rollout:** mismo release; Cemu+Servidor se desplegan juntos, teléfono
  sin cambios.

## 7. Execution checklist

- [x] DES-004 actualizado (1.14.0, changelog con CR-051)
- [x] Tests: TEST-0113 nuevo, TEST-072 extendido
- [x] `testing/traceability.md` (fila TEST-0113 + notas TEST-072 + history)
- [x] `_index.md` del registro de CRs
- [x] Código en server + Cemu compilado con gates verdes

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Buffy (agente) | Opened and applied en la misma iteración |
