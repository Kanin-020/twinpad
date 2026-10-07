# Traceability Matrix — Twinpad

> The registry of all traceability links. Each row is written here once; documents reference IDs, this matrix is the complete map.
> Direction: `REQ → SPEC → DES → TEST`, plus `CR` changes and `ADR` justifications.

## Next free IDs

| Type | Next |
|---|---|
| TEST | TEST-001 |

> 💡 TEST IDs are reserved here and defined physically in the test code (see test-strategy.md §4).

## REQ → SPEC → DES → TEST

| REQ | SPEC | DES | TEST | Status | Notes |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

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
| — | — | — |

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
