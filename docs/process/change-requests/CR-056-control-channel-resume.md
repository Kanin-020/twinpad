# Change Request (CR) — CR-056 — Twinpad

| | |
|---|---|
| ID | CR-056 |
| Title | Reanudación del canal de control cableada de punta a punta: retención de ligadura + mensaje `pair_resume` (SPEC-001 §8.2) |
| Type | Correction (ambiguity) |
| Status | `Open` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | — |
| Decided by | — |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-001, SPEC-002, SPEC-003 |
| Sections | SPEC-001: §2, §4.2, §6, §8.2 (rewrite), §9, §10, §14, §16, Changelog · SPEC-002: §6 · SPEC-003: §3, §4.2 |
| Current version → new version | SPEC-001 `1.1.0 → 1.2.0` (tras CR-055) · SPEC-002 `1.10.0 → 1.11.0` · SPEC-003 `1.11.0 → 1.12.0` (rebasear al aplicar) |

## 2. Requested change

**Current:**

```text
SPEC-001 v1.0.0 §8.2 (Reanudación): "Si el cliente pierde la conexión y
reconecta en 10 s presentando pair_proof + session_id, el servidor puede
re-admitirlo sin re-escanear el QR". Ambigüedades y huecos:

1. El mensaje `pair_proof` de §4.2 no tiene campo `session_id`: no existe
   ningún mensaje wire que presente ambos sin re-escanear.
2. §8.2 dice que el servidor entrega "pair_ack con nuevo session_id", pero el
   canal media (SPEC-003 §4.2) autentica por session_id + session_key: si el
   session_id cambia, todas las credenciales media del teléfono quedan
   inválidas y la reanudación total (video) rompe.
3. TEST-007 implementa el "resume" a nivel de registro, pero el EOF del
   socket de control ejecuta registry.remove() que destruye también la
   session_key: ningún resume posterior puede validar el pair_proof.
4. En la app, la caída del socket de control en S3/Linked va directa a
   Failure(SessionEnded) con exigencia de re-scan (PairingStateMachine), sin
   intento de §8.2.
```

**Proposed:**

```text
§2 — añadir definición:

- **Sesión retenida**: estado de la ligadura tras la caída del socket de
  control, durante RETENTION_MS = 60 000 ms, en el que el servidor conserva
  session_id + session_key y admite la reanudación de §8.2 con `pair_resume`.

§4.2 — añadir mensaje:

| `pair_resume` | app → servidor | `type` | string | fijo `pair_resume` | yes |
| | | `session_id` | string | base64url, decodifica 16 bytes; ligadura existente no expirada | yes |
| | | `nonce_c` | string | base64url, 32 bytes, fresco | yes |
| | | `proof` | string | base64url, 32 bytes | yes |

`proof = HMAC-SHA256(key = session_key, data = "twinpad.v1 resume" || session_id || nonce_c)`.

§6 — añadir estado:

- **S4 — Sesión retenida** (lado servidor: ligadura viva sin socket; lado
  app: Linked degradado, intentando reanudar). Transiciones: S3 -(corte del
  socket de control)- S4; S4 -(`pair_resume` válido)- S3 con el mismo
  session_id; S4 -(retención > 60 000 ms)- S1 con destrucción de la ligadura
  (requiere QR).

§8.2 — reescribir:

Reanudación del canal de control: dada una sesión retenida (S4), el cliente
reconecta y envía `pair_resume` con session_id + pair_proof (mismo HMAC §5)
+ nonce_c fresco. Si es válido y la retención no expiró, el servidor
re-admite la sesión y entrega `pair_ack`al con el MISMO session_id (el canal
media ya anclado a ese session_id sigue usable). La ventana es de 60 000 ms
(RETENTION_MS); vencida, exige re-scan. Numeración de reintentos ilimitada
dentro de la ventana.

§9 — código E3 se aplica también a `pair_resume` con session_id desconocido,
  retención expirada o proof inválido (respuesta sin desglosar causa).

§14 — reescribir la cláusula de reinicio:

- Un reinicio del servidor elimina todas las ligaduras (la reanudación §8.2
  ya no es posible): la retención es solo memoria de proceso, alineada con
  el almacenamiento de la session_key (R2).
- Cada `pair_resume` aceptado reutiliza el session_id retenido; el servidor
  no lo reemplaza durante la retención.

§10 — edge cases añadidos: retención expirada justo en el intento; resume
  con app különboző session_id; servidor reiniciado durante la retención;
  doble resume simultáneo.

§16 — filas nuevas:

| Aspect | Verified by |
|---|---|
| Retención y destruction de ligadura a los 60 000 ms | TEST-0123 |
| `pair_resume` válido y rechazado (proof, session_id, expiración) | TEST-0124 |
| App: corte de control en S3 → reintento automático ≤ 60 s sin QR | TEST-0125 |

SPEC-002 §6 — transición U4→U1 «corte de conexión» se subdivide: U4 pasa a
U6 «Reconectando» (estado nuevo) que intenta `pair_resume` (SPEC-001 §8.2)
mientras la retención viva; U6→U4 en `pair_ack`; U6→U1 «Sesión terminada» al
expirar la ventana o agotar reintentos. SPEC-002 `1.10.0 → 1.11.0`.

SPEC-003 §3 / §4.2 — la precondición «el servidor valida que la sesión y el
socket de control sigan activos» pasa a «…sigan activos o retención viva
(SPEC-001 §8.2)»: el teléfono reúne sus credenciales de media mientras el
control esté en S4. SPEC-003 `1.11.0 → 1.12.0`.
```

## 3. Motivation

Continuación de la revisión de cuellos de botella (CR-055): hoy cualquier
corte del socket de control destruye la clave (registry.remove) y la app
obliga a re-escanear el QR. La ventana de 10 s de REQ-005 AC-2 solo cubre la
reconexión del canal media; un corte del control (INFRAESTRUCTURA igual de
frágil sobre Wi-Fi) requiere re-pairing manual completo. Además, §8.2 tal
como está escrito es inejecutable: no existe mensaje wire que lleve ambos
campos y el texto «nuevo session_id» rompería el canal media anclado.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | QUERY ya exige «sesión reutilizable»; §8.2 la realiza de verdad | none |
| SPEC-001 | yes | §2/§4.2/§6/§8.2/§9/§14/§16 | update (1.2.0) |
| SPEC-002 | yes | estado U6 nuevo en §6 | update (1.11.0) |
| SPEC-003 | yes | precondición §3/§4.2 «activa o retenida» | update (1.12.0) |
| ARCH | no | el resume vive en el socket de control y registry existentes | none |
| DES-001 / DES-002 | yes | máquina S4 en servidor; PairingStateMachine con estado Reconectando | update al implementar |
| TEST-0123/0124/0125 | yes | reservados: 0123 server loopback (retención), 0124 core (validación pure), 0125 android | add rows al aplicar |
| Code | yes | `core/` (mensaje pair_resume), `server/` (retención + resume), `android/` (reintentos) | al implementar |
| Data / migrations | no | — | none |

## 5. Options considered

1. **Retención de 60 s con `pair_resume` (propuesto)** — cumple §8.2 tal cual,
   conserva el session_id (no rompe credenciales media) y la session_key
   nuca sale del servidor.
2. Añadir `session_id` como campo opcional al `pair_proof` existente —
   menor cambio wire, pero cruza preocupaciones: el handshake inicial no
   conoce session_id aún; un solo mensaje para dos propósitos complica §9.
3. Do nothing — cualquier corte del control exige QR nuevo; la conexión
   nunca será estable según NFR-006 y el criterio del owner.

## 6. Decision

- **Outcome:** (pendiente) — ventana de retención de 60 000 ms y preservación
  del session_id a confirmar por el owner al aplicar.
- **Rationale:** cierra la única ambigüedad ejecutable que bloquea la
  reanudación §8.2 y alinea REQ-002 «sesión reutilizable», SPEC-002 U4→U1 y
  SPEC-003 §3.
- **Rollout:** mismo release; Servidor y app Android actualizados juntos.

## 7. Execution checklist

- [ ] Target document(s) updated
- [ ] Dependent documents updated
- [ ] Tests added/updated (TEST-0123..0125)
- [ ] `testing/traceability.md` updated (including history)
- [ ] Indexes updated
- [ ] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened en lote de cuellos de botella de conexión |
