# Change Request (CR) — CR-099 — Twinpad

| | |
|---|---|
| ID | CR-099 |
| Title | Reanudación de sesión (`pair_resume`) estable: heartbeat y relay UDP tras reconectar |
| Type | `Clarification` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-12 |
| Decided | 2026-11-12 |
| Decided by | Product owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-001 (§8.2 reanudación, §4 heartbeat), SPEC-003 (§4.4/§8.5 relay UDP tras reanudar) |
| Sections | SPEC-001 §8.2 y §4; SPEC-003 §4.4 y §8.5 |
| Current version → new version | SPEC-001 1.3.0 → 1.4.0; SPEC-003 1.15.0 → 1.16.0 |

## 2. Requested change

**Current:**

```text
SPEC-001 §8.2 define que pair_resume restablece el canal de control con el
mismo session_id y session_key (CR-056). No define el estado del heartbeat
(ping/pong, HB3/HB4) ni del relay UDP (join/binding) tras la reanudación.
```

**Proposed:**

```text
Tras un pair_resume aceptado, el servidor y el teléfono reinician el
heartbeat desde cero (contador de ping, último pong, watchdog HB4) y el
canal media UDP se re-enlaza con un nuevo JOIN antes de contar el silencio
de medios. Se especifica qué línea puede ser la primera de un socket nuevo
(solo pair_request, pair_proof o pair_resume; cualquier otro tipo → E4).
```

## 3. Motivation

Evidencia en banco (2026-11-12, Cemu + Android por LAN):

- El `pair_resume` se acepta y `StreamStart` vuelve a emitirse. Cemu sigue enviando AU (`TwinpadMediaDiag sent` sube, `fail=0`).
- El teléfono recibe el primer `pair_resume` correcto, pero a los pocos segundos el servidor corta por `HB4 — sin pong válido en 15 s` o por `error de E/S`.
- En el relay UDP aparece `park: media_game no coincide` y `binding sin keepalive 10000 ms`; no se reenvían AU nuevas al teléfono tras la reanudación.
- Antes de la reanudación, el teléfono enviaba `input_report`, `video_config` y `game_select` como primera línea del socket nuevo (E4). Se corrigió en la app con lista blanca de handshake, pero el ciclo de heartbeat y relay sigue cayendo.

No hay causa confirmada en un solo lado; el protocolo no define el estado de heartbeat ni de relay tras reanudar, y eso impide validar la implementación.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | Requisito de LAN sin cambio | none |
| SPEC-001 | yes | §8.2 y §4 deben definir el estado tras reanudar | update |
| SPEC-003 | yes | §4.4/§8.5 deben definir el re-JOIN tras reanudar | update |
| ARCH | no | Sin cambio de límites | none |
| DES-004 | no | Sin cambio de contrato con Cemu | none |
| TEST-XXX | yes | Nuevo test de reanudación (heartbeat y relay) | add |
| Code | yes | Servidor (`session.rs`, `heartbeat.rs`, `media_udp.rs`) y app (`TcpPairingClient.kt`, `PairingMediaSession.kt`) | PR por lado |
| Data / migrations | no | Ninguno | none |
| Integrations / contracts | yes | Línea de handshake permitida al abrir socket | version bump SPEC-001 |

## 5. Options considered

1. Especificar el estado tras reanudar en SPEC-001/SPEC-003 y añadir tests de reanudación — evita parches locales sin contrato.
2. Seguir con parches locales en app y servidor — cada prueba cuesta un ciclo de teléfono y no hay criterio de aceptación.
3. No reanudar: pedir QR nuevo tras cada corte — funciona hoy, pero no cumple la reanudación de CR-056.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner aprobó la opción 1 (2026-11-12): especificar el estado tras reanudar en SPEC-001/SPEC-003 y añadir tests de reanudación, en lugar de seguir con parches locales sin contrato. El protocolo no definía heartbeat ni relay UDP tras `pair_resume`, y eso impedía validar la implementación.
- **Rollout:** SPEC-001 1.4.0 (§4.3 HB7, §8.2 primera línea y estado tras reanudar, §16) y SPEC-003 1.16.0 (§4.4 re-JOIN UDP, §8.5, §16) aplicados en el mismo cambio. Tests TEST-0189, TEST-0190 y TEST-0191 registrados en `traceability.md` como `Draft` hasta su implementación. El código (servidor y app) se implementa en PR aparte, sin tocar el contrato.

## 7. Execution checklist

- [x] Aprobación del owner
- [x] Actualizar SPEC-001 y SPEC-003
- [x] Tests de reanudación en traceability (TEST-0189/0190/0191, `Draft`)
- [ ] Implementar HB7 y re-JOIN UDP en servidor y app; pasar TEST-0189/0190/0191 a `Passed`
- [ ] Retirar diagnósticos temporales (servidor `DIAG`, Cemu `TwinpadReconnect`/`TwinpadMediaDiag`, app)
