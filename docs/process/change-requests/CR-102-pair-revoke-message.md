# Change Request (CR) — CR-102 — Twinpad

| | |
|---|---|
| ID | CR-102 |
| Title | `pair_revoke`: mensaje app→servidor para revocar el registro persistente al pulsar «Desconectar dispositivo» (CR-094 §2.2) |
| Type | `Correction` (hueco de contrato en CR-094) |
| Status | `Open` |
| Author | Buffy (agente) |
| Created | 2026-11-17 |
| Decided | `-` |
| Decided by | `-` |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-001`, `REQ-002` (AC), `SPEC-002` (U4/§6) |
| Sections | SPEC-001 §4.2 (tipos de mensaje), §8.3 (revocación), §12 (contratos) |
| Current version → new version | `1.5.0 → 1.5.1` |

## 2. Requested change

**Current** (SPEC-001 §4.2, lista cerrada de tipos):

```text
pair_request | pair_challenge | pair_proof | pair_ack | pair_error | ping | pong | pair_resume
```

**Current** (SPEC-001 §8.3):

```text
- Trigger: el Jugador revoca el emparejamiento desde la app o desde la PC.
- Flow: el propietario de la credencial borra `session_id`, la `session_key` y
  el binding del almacén de sesiones.
```

No existe ningún mensaje app→servidor que permita cumplir CR-094 §2.2 («el
servidor revoca el registro») desde el botón de la app: `pair_resume` solo
revalida, y el cierre de socket no distingue «desconectar» de «corte».

**Proposed** — añadir un tipo al wire (solo app→servidor, primera línea de un
socket nuevo, igual que `pair_request`/`pair_proof`/`pair_resume`):

```text
{"type":"pair_revoke","session_id":"<base64url 16 B>","proof":"<base64url 32 B>"}

proof = HMAC-SHA256(key = session_key, data = "twinpad.v1 revoke" ‖ session_id)
```

```text
- Servidor: verifica `session_id` conocido (retención viva o registro en
  disco) y `proof`; borra la entrada del `DeviceStore` (y la retención, si
  existe). Responde `pair_ack` con el mismo `session_id` y cierra el socket.
- `session_id` desconocido o `proof` inválido → `pair_error` E3 (§9), sin
  borrar nada.
- La app borra la vinculación local después de enviar el mensaje y no espera el
  `pair_ack` para cerrar la UI (el dispositivo revocado ya no reanuda).
```

## 3. Motivation

Implementation discovery al implementar CR-094 §2.2: la parte local («Desconectar»
olvida la vinculación) está implementada y cubierta (TEST-0195), pero la
revocación en el servidor no se puede codificar sin inventar un tipo de mensaje
—prohibido por SPEC-001 §4.2/§12 y por `AGENTS.md`—. Sin esto, el criterio de
aceptación ««Desconectar dispositivo» en Android revoca el registro en el
servidor» (CR-094 §5) y «Un dispositivo revocado vuelve a exigir QR nuevo»
quedan sin cumplir.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | yes | AC de revocación gana el disparador desde la app | update |
| SPEC-001 | yes | §4.2 (nuevo tipo), §8.3 (flow con wire), §9 (E3 ya cubre proof inválido), §12 (contrato nuevo) | update |
| SPEC-002 | yes | el botón «Desconectar» envía `pair_revoke` antes de olvidar la vinculación | update |
| ARCH | no | mismo canal TCP de control | none |
| DES-001 | yes | mensaje nuevo en el codec de handshake | update |
| TEST-0195 | yes | se amplía: el servidor borra la fila del `DeviceStore` y un `pair_resume` posterior da E3 | update |
| Code | yes | ~40 líneas Rust (`core/src/message.rs`, `server/src/session.rs`, `registry.rs`) + ~10 Kotlin (cliente de pairing) | 1 PR |

## 5. Acceptance

- [ ] «Desconectar dispositivo» envía `pair_revoke` con proof válido.
- [ ] El servidor borra el registro; el dispositivo desaparece de la UI local.
- [ ] Un `pair_resume` posterior con esa credencial recibe E3.
- [ ] `proof` inválido o `session_id` desconocido → E3 sin efectos.

## 6. Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-11-17 | Buffy (agente) | Propuesta inicial (hueco de contrato detectado al implementar CR-094 §2.2) |
