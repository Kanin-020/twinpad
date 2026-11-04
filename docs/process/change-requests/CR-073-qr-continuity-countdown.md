# Change Request (CR) — CR-073 — Twinpad

> ⚠️ TEMPLATE — the ONLY mechanism to change an `Approved` document. One CR per logical change.

> **What this is:** a controlled, reviewable change to an approved artifact (REQ, SPEC, ARCH, DES, guidelines, strategy). It records what, why, impact and decision.
> **What it is NOT:** a bug report (use the issue tracker) or a discussion thread — keep it decidable.

| | |
|---|---|
| ID | CR-073 |
| Title | Continuidad del QR en la página local: contador de validez y regeneración automática/manual SIN recargar (ruta `GET /qr`) |
| Type | `New behavior` |
| Status | `Applied` (owner-requested) |
| Author | Buffy (agente) |
| Created | 2026-11-04 |
| Decided | 2026-11-04 |
| Decided by | Owner (solicitud directa en la iteración actual) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-010`, `DES-005` |
| Sections | SPEC-010 §2, §4, §5, §6, §7, §9, §10; DES-005 §1, §4, §6, §10, §12 |
| Current version → new version | SPEC-010 `0.3.0 → 0.4.0`; DES-005 `0.3.1 → 0.4.0` |

## 2. Requested change

**Current (SPEC-010):**

```text
- NFR-003 (bajo consumo): la página es estática; sin polling del server
  por la página; el tray no sondea.
W8 — QR siempre fresco: la página (y el URI del tooltip) muestran el URI
vigente del momento de la consulta; si el token de 300 s (R7) venció, la
consulta fuerza la emisión de uno nuevo. El QR en pantalla y el URI
copiable pertenecen AL MISMO token de la respuesta.
```

**Proposed (SPEC-010):**

```text
§2 — nueva definición:
- Ruta /qr: subruta HTTP del puerto_local (loopback only, W6) que devuelve
  el vencimiento del QR vigente en JSON: {uri, ttl_ms, svg}; el svg es el
  QR del MISMO token que el uri (W8).

§4 — nueva fila de Inputs:
| GET /qr (contador del QR) | HTTP request | desde 127.0.0.1 | vencimiento JSON del QR vigente (§5 W9) |

§5 — nueva regla:
- W9 — Continuidad del QR sin recargar: la página muestra un contador
  (mm:ss) con los ms restantes de validez del QR vigente; al vencer, la
  página consulta GET /qr (UNA consulta por ciclo del token) y sustituye
  EL QR Y LA CASILLA DEL URI juntos con el MISMO token nuevo (W8), sin
  recargar la página. El botón «Regenerar QR» ejecuta la misma consulta
  manualmente. Fail-soft (W7): sin respuesta válida (incluido 503 cuando
  el SVG no es renderizable), la página conserva el QR y el URI vigentes
  y reintenta con cadencia fija (15 s).

§6 — nueva fila de Outputs:
| Vencimiento JSON | GET /qr (200) | W9 |

§7 — nuevo edge case:
- E7 — SVG no renderizable en GET /qr: 503; la página conserva el QR y el
  URI vigentes en pantalla (W7) hasta la próxima consulta.

§9 — nuevo AC:
- AC-5: dado el server activo, GET /qr desde loopback responde 200 con
  JSON que contiene el uri vigente, ttl_ms ∈ (0; 300 000] y el svg del
  MISMO token (W8); desincronizar QR y casilla es imposible por diseño
  (misma respuesta). TEST-0146. La cadencia del contador y el swap
  visual sin recarga son verificación manual (owner).
- AC-6 (0.2.0, decisión del owner): dado el server activo, GET /qr/rotate
  desde loopback invalida el token vigente NO CONSUMIDO y emite uno
  nuevo de inmediato (semántica de «Regenerar QR»); tokens distintos por
  llamada y el URI anterior quedan fuera (E2/E3 via `lookup` reutilizando
  §8.3). TEST-0147 (endpoint) y TEST-0148 (semántica de rotación). El
  vencimiento automático NO rota: solo reconsulta `GET /qr` (R7 intacto).

§10 — NFR-003 reescrito:
- NFR-003 (bajo consumo): la página es estática en su carga; el SONDEO
  queda limitado al ciclo de vida del token: UNA consulta GET /qr por
  vencimiento (o por regeneración manual). Sin polling de estado de
  sesiones ni del server; el tray no sondea.
```

## 3. Motivation

El owner solicitó un contador de tiempo que muestre cuánto le queda al QR y que, al vencer, se reemplace automáticamente por uno nuevo, además de un botón «Regenerar QR» que sustituye el URI vigente y el QR SIN recargar la página. Con la página estática actual (W8 por consulta), el operador puede car lelvar en pantalla un QR vencido hasta que refresque manualmente y no hay indicador de validez. La frescura por consulta (W8) se conserva intacta: cada `GET /qr` fuerza el ciclo R7 (`encamera_fresh_qr`), y el swap cliente no introduce ninguna vía nueva de parada ni de estado de sesiones.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-001 | no | (la búsqueda es operacional/presentación; AC-1/AC-2 intactos) | none |
| SPEC-010 | yes | Nuevo W9/E7/AC-5, nueva ruta input/output, NFR-003 reescrito | update → 0.4.0 |
| ARCH | no | La ruta vive en el adaptador webui existente; sin componentes nuevos | none |
| DES-005 | yes | Ruta `/qr`, payload JSON, cálculo TTL, fail-soft | update → 0.4.0 |
| TEST-0135/0136 | no | Regresión: los anclas del markup (`<textarea readonly`, «Sesiones activas: N», `/stop`) se conservan | none (regresión) |
| TEST-0146 | yes (nuevo) | Integración del endpoint `/qr` (uri+ttl+svg mismo token) | add |
| Code | yes | `tokens.rs` (+1 método TTL), `webui.rs` (ruta, deps TTL, helpers JSON), `main.rs` (wiring), `server/ui/` (dashboard 2 columnas + contador) | PR de esta iteración |
| Data / migrations | no | — | none |
| Integrations / contracts | no | HTTP artesanal interno, sin SDK nuevo | none |

## 5. Options considered

1. **Propuesto** — ruta `GET /qr` (JSON: uri+ttl_ms+svg del mismo token) + contador cliente: una consulta por ciclo de token, sin recarga, sin dependencias nuevas y sin inventar rendering de QR en JS.
2. Do nothing — el operador vive sin indicador y puede intentar vincular con un QR vencido.
3. Full page auto-reload cada N s — viola el objetivo (recarga completa innecesaria) y multiplica consultas del estado completo; descartado.
4. SSE/websocket para vencimiento push — introduciría un canal de eventos permanente (= polling permanente en la práctica) contra el espíritu de NFR-003 y la superficie artesanal; descartado.

## 6. Decision

- **Outcome:** `Approved` (aplicada dentro de la misma iteración)
- **Rationale:** la única consulta nueva es 1/ciclo de token (≤ 1 cada 300 s) y reutiliza exactamente el mecanismo W8/R7 ya implementado; desincronizar QR y URI es imposible porque viajan en la misma respuesta.
- **Rollout:** same release.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-010 0.4.0, DES-005 0.4.0, changelog con referencia a CR-073)
- [x] Dependent documents updated (des-005; impact table revisado contra `testing/traceability.md`)
- [x] Tests added/updated (TEST-0146)
- [x] `testing/traceability.md` rows updated (fila TEST-0146 + tabla de historial de CR)
- [x] Indexes (`_index.md`) updated (next free IDs de CR y TEST)
- [x] Code change merged (tokens/webui/main + assets de UI)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-04 | Buffy (agente) | Opened y aplicado con autorización del owner (contador + regeneración sin recargar; TEST-0146) |
| 0.2.0 | 2026-11-04 | Buffy (agente) | El owner aclaró que «Regenerar QR» debe generar un token NUEVO (no reconsultar el vigente sin efecto visible): el botón pasa a `GET /qr/rotate`, que invalida el vigente no consumido y emite otro de inmediato (reutiliza §8.3 de SPEC-001); vencimiento automático sin rotación (R7 intacto); AC-6; TEST-0147/0148 |
