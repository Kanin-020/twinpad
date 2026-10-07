# Traceability Matrix — Twinpad

> The registry of all traceability links. Each row is written here once; documents reference IDs, this matrix is the complete map.
> Direction: `REQ → SPEC → DES → TEST`, plus `CR` changes and `ADR` justifications.

## Next free IDs

| Type | Next |
|---|---|
| TEST | TEST-015 |

> 💡 TEST IDs are reserved here and defined physically in the test code (see test-strategy.md §4).

## REQ → SPEC → DES → TEST

| REQ | SPEC | DES | TEST | Status | Notes |
|---|---|---|---|---|---|
| REQ-002 | SPEC-001 | DES-001 | TEST-001 | Approved | Flujo principal del Handshake (SPEC-001 §7) — implementado en `core::handshake` |
| REQ-002 | SPEC-001 | DES-001 | TEST-002 | Approved | E1 servidor no disponible (REQ-001 AC-3) — pendiente del adaptador `server/` |
| REQ-002 | SPEC-001 | DES-001 | TEST-003 | Approved | E2 QR expirado (REQ-002 AC-3) — implementado en `core::crypto::is_expired` |
| REQ-002 | SPEC-001 | DES-001 | TEST-004 | Approved | E3 sin token / consumido / proof inválido (NFR-004, REQ-002 AC-2) — implementado en `core::handshake` |
| REQ-002 | SPEC-001 | DES-001 | TEST-005 | Approved | E3 segundo dispositivo con el mismo QR — implementado en `core::handshake` |
| REQ-002 | SPEC-001 | DES-001 | TEST-006 | Approved | E4 mensaje malformado / schema desconocido — implementado en `core::message` + `core::qr` (E5) |
| REQ-002 | SPEC-001 | DES-001 | TEST-007 | Approved | Reanudación de sesión (SPEC-001 §8.2) — pendiente del registro de sesiones en `server/` |
| REQ-002 | SPEC-001 | DES-001 | TEST-008 | Approved | Revocación del emparejamiento (SPEC-001 §8.3) — pendiente del registro en `server/` |
| REQ-002 | SPEC-001 | DES-001 | TEST-009 | Approved | R7: regeneración automática del QR — pendiente del temporizador en `server/` |
| REQ-002 | SPEC-002 | - | TEST-010 | Approved | Flujo completo en la app: escaneo → Vinculado (SPEC-002 §7) |
| REQ-002 | SPEC-002 | - | TEST-011 | Approved | E1 Servidor no disponible desde la app (SPEC-002 §9) |
| REQ-002 | SPEC-002 | - | TEST-012 | Approved | E5 QR inválido + permiso de cámara denegado (SPEC-002 §8.1/§9) |
| REQ-002 | SPEC-002 | - | TEST-013 | Approved | E2/E3 mensajes exactos desde `pair_error` (REQ-002 AC-3, SPEC-002 §9) |
| REQ-002 | SPEC-002 | - | TEST-014 | Approved | Edge cases de UI: cancelar, segundo plano, código desconocido, rotación (SPEC-002 §10) |

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
