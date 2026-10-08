# Traceability Matrix — Twinpad

> The registry of all traceability links. Each row is written here once; documents reference IDs, this matrix is the complete map.
> Direction: `REQ → SPEC → DES → TEST`, plus `CR` changes and `ADR` justifications.

## Next free IDs

| Type | Next |
|---|---|
| TEST | TEST-024 |

> 💡 TEST IDs are reserved here and defined physically in the test code (see test-strategy.md §4).

## REQ → SPEC → DES → TEST

| REQ | SPEC | DES | TEST | Status | Notes |
|---|---|---|---|---|---|
| REQ-002 | SPEC-001 | DES-001 | TEST-001 | Approved | Flujo principal del Handshake (SPEC-001 §7) — implementado en `core::handshake` |
| REQ-002 | SPEC-001 | DES-001 | TEST-002 | Approved | E1 servidor no disponible (REQ-001 AC-3) — aspecto servidor: cierre tras rechazo verificado en loopback (`server/tests/loopback_integration.rs`); aspecto cliente en TEST-011 |
| REQ-002 | SPEC-001 | DES-001 | TEST-003 | Approved | E2 QR expirado (REQ-002 AC-3) — implementado en `core::crypto::is_expired` |
| REQ-002 | SPEC-001 | DES-001 | TEST-004 | Approved | E3 sin token / consumido / proof inválido (NFR-004, REQ-002 AC-2) — implementado en `core::handshake` + E3 por el wire en loopback |
| REQ-002 | SPEC-001 | DES-001 | TEST-005 | Approved | E3 segundo dispositivo con el mismo QR — implementado en `core::handshake` + R2 en `server::tokens` y por el wire |
| REQ-002 | SPEC-001 | DES-001 | TEST-006 | Approved | E4 mensaje malformado / schema desconocido — implementado en `core::message` + `core::qr` (E5) + E4 por el wire en loopback |
| REQ-002 | SPEC-001 | DES-001 | TEST-007 | Approved | Reanudación de sesión (SPEC-001 §8.2) — pendiente del registro de sesiones en `server/` |
| REQ-002 | SPEC-001 | DES-001 | TEST-008 | Approved | Revocación del emparejamiento (SPEC-001 §8.3) — `TokenStore::revoke` implementado y testeado; revocación por UI pendiente |
| REQ-002 | SPEC-001 | DES-001 | TEST-009 | Approved | R7: regeneración automática del QR — implementado en `TokenStore::ensure_fresh_qr` + listener del binario (unit + integración) |
| REQ-002 | SPEC-002 | DES-002 | TEST-010 | Approved | Flujo completo en la app: escaneo → Vinculado (§7) — implementado, proof verificado con HMAC independiente |
| REQ-002 | SPEC-002 | DES-002 | TEST-011 | Approved | E1 Servidor no disponible desde la app (§9) — implementado |
| REQ-002 | SPEC-002 | DES-002 | TEST-012 | Approved | E5 QR inválido + permiso de cámara denegado (§8.1/§9) — implementado |
| REQ-002 | SPEC-002 | DES-002 | TEST-013 | Approved | E2/E3 mensajes exactos desde `pair_error` (REQ-002 AC-3, §9) — implementado |
| REQ-002 | SPEC-002 | DES-002 | TEST-014 | Approved | Edge cases de UI: cancelar, segundo plano, código desconocido, reintento (§10) — implementado |
| REQ-004 | SPEC-003 | `-` | TEST-015 | Approved | Flujo principal del stream de video (SPEC-003 §7, REQ-004 AC-1) — implementado: codec en `core::stream` (10 tests) + fuentes en `server::video` (escritorio con `twinpad-capture::DesktopGrabber` y patrón demo) + 100 frames verificados por TCP real |
| REQ-004 | SPEC-003 | `-` | TEST-016 | Approved | Frame corrupto/tardío y edge cases §10 (REQ-004 AC-3) — codec estricto E4 en `core::stream` + descarte en `StreamStateMachine` (app) |
| REQ-005 | SPEC-003 | `-` | TEST-017 | Approved | Reanudación tras corte breve < 10 s sin re-pairing (§8.2, REQ-005 AC-2) — implementada en el server (sesión persistente); caso de red en banco físico |
| REQ-003 | SPEC-004 | `-` | TEST-018 | Approved | Flujo táctil → juego y orden por `seq` (SPEC-004 §7, REQ-003 AC-2) — transporte inicial: sesión LAN de SPEC-001 (OQ §17); implementación en curso |
| REQ-003 | SPEC-004 | `-` | TEST-019 | Approved | Rumble (AC-3) y edge cases §10 — postergado a la iteración de rumble (requiere canal PC→app) |
| REQ-005 | SPEC-005 | `-` | TEST-020 | Draft | Flujo principal del audio (SPEC-005 §7, REQ-005 AC-1) — pendiente de DES |
| REQ-006 | SPEC-006 | `-` | TEST-021 | Approved | Detección automática y liveness (SPEC-006 §7, REQ-006 AC-1) — transporte: TCP localhost NDJSON (ADR-008); codec en `core::game` + registro en `server::games` + SDK Rust |
| REQ-007 | SPEC-007 | `-` | TEST-022 | Draft | Carga/activación/rechazo de Config externa (REQ-007 AC-1..3) — pendiente de DES |
| REQ-008 | SPEC-008 | `-` | TEST-023 | Draft | Edición, carga automática y conflicto de superficie (REQ-008 AC-1..3) — pendiente de DES |

## NFR verification

| NFR | Verified by | Method (test / monitoring) | Threshold |
|---|---|---|---|
| NFR-001 | `-` | TEST de performance (captura óptica, banco de pruebas) | p95 ≤ 60 ms |
| NFR-002 | `-` | TEST de performance (inyección HID sintética) | p95 ≤ 15 ms |
| NFR-003 | `-` | TEST de monitoreo de recursos + revisión | ≤ 2 % CPU idle |
| NFR-004 | `-` | TEST de seguridad (rechazo sin token) | 100 % rechazo |
| NFR-005 | `-` | TEST en dispositivo físico | ≤ 25 % CPU @ 30 fps |
| NFR-006 | `-` | TEST de concurrencia (2 dispositivos) | degradación ≤ 10 % |
| NFR-007 | `-` | TEST de batería (Battery Historian) | ≤ 20 %/h |

## Acceptance criteria coverage

| REQ | AC | Verified by |
|---|---|---|
| REQ-001 | AC-3 | TEST-002 |
| REQ-002 | AC-1 | TEST-001 |
| REQ-002 | AC-2 | TEST-004, TEST-005 |
| REQ-002 | AC-3 | TEST-003 |

## ADR links

| ADR | Justifies / affects |
|---|---|
| ADR-001 | ARCH §2 (stack Kotlin/Rust) |
| ADR-002 | ARCH §1, §4 (transportes) |
| ADR-003 | ARCH §2, §6 (HID nativo) |
| ADR-004 | ARCH §4, §7 (pairing QR) |
| ADR-005 | ARCH §2, §4 (framebuffers) |
| ADR-006 | ARCH §2, §6 (modelos de plugin) |
| ADR-007 | SPEC-004 §17 (transporte inicial de inputs por sesión LAN) |
| ADR-008 | SPEC-006 §4/§17 (IPC del SDK por TCP localhost NDJSON) |

## Change history

| CR | Changes | Date | Status |
|---|---|---|---|
| — | — | — | — |

## Invariants of this matrix

1. Every Approved SPEC appears in ≥ 1 row of the first table.
2. Every REQ marked `Implemented` has all its ACs covered in the third table.
3. Every TEST ID used in any doc exists in the first or second table.
4. Every CR that changed a SPEC has a row in the change history.
5. Broken links in this matrix are release blockers.
