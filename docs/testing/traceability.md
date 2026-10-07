# Traceability Matrix — Twinpad

> The registry of all traceability links. Each row is written here once; documents reference IDs, this matrix is the complete map.
> Direction: `REQ → SPEC → DES → TEST`, plus `CR` changes and `ADR` justifications.

## Next free IDs

| Type | Next |
|---|---|
| TEST | TEST-010 |

> 💡 TEST IDs are reserved here and defined physically in the test code (see test-strategy.md §4).

## REQ → SPEC → DES → TEST

| REQ | SPEC | DES | TEST | Status | Notes |
|---|---|---|---|---|---|
| REQ-002 | SPEC-001 | - | TEST-001 | In review | Flujo principal del Handshake (SPEC-001 §7) |
| REQ-002 | SPEC-001 | - | TEST-002 | In review | E1 servidor no disponible (REQ-001 AC-3) |
| REQ-002 | SPEC-001 | - | TEST-003 | In review | E2 QR expirado (REQ-002 AC-3) |
| REQ-002 | SPEC-001 | - | TEST-004 | In review | E3 sin token / consumido / proof inválido (NFR-004, REQ-002 AC-2) |
| REQ-002 | SPEC-001 | - | TEST-005 | In review | E3 segundo dispositivo con el mismo QR |
| REQ-002 | SPEC-001 | - | TEST-006 | In review | E4 mensaje malformado / schema desconocido |
| REQ-002 | SPEC-001 | - | TEST-007 | In review | Reanudación de sesión (SPEC-001 §8.2) |
| REQ-002 | SPEC-001 | - | TEST-008 | In review | Revocación del emparejamiento (SPEC-001 §8.3) |
| REQ-002 | SPEC-001 | - | TEST-009 | In review | R7: regeneración automática del QR al expirar |

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
