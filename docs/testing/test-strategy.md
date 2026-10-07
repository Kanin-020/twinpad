# Test Strategy — Twinpad

> ⚠️ TEMPLATE — replace every `<placeholder>`, delete guidance blocks (`> 💡 …`) before `Approved`.

> **What this is:** how the project demonstrates that the implementation complies with the specifications. It defines the test levels, what each level owns, and how SPEC ↔ TEST traceability works.
> **What it is NOT:** the list of test cases (tests live in the code, linked here by TEST IDs) and not the behavior itself (SPECs own behavior).

| | |
|---|---|
| ID | TEST-S |
| Version | 0.1.0 |
| Status | Draft |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |

## 1. Objectives

> 💡 What "verified" means for this project. Tie to NFRs by ID where applicable.

- Prove compliance with every Approved SPEC.
- Detect regressions in behavior before merge.
- NFR-001 (latencia de Segunda pantalla) y NFR-002 (input lag HID) validados en cada release con dispositivo físico.
- NFR-004 (seguridad del pairing) validado con tests de seguridad automatizados.

## 2. Test levels and ownership

> 💡 What each level is responsible for. The ownership column is the rule that prevents duplicate coverage.

| Level | Verifies | Lives in | Runs | Owns |
|---|---|---|---|---|
| Unit | Dominio de `core/`, reglas (SPEC §11), encode/decode HID, mapeo táctil | `core/src/**_test.rs`, `android/**/src/test/` | every commit | Eng |
| Integration | Componentes reales en loopback: core↔client, cargador de configs, SDK mock del juego | `server/tests/`, `android/**/src/androidTest/` | every PR | Eng |
| Contract | Contratos de SPEC §12: payload QR, protocolo de sesión, formato Config externa, descriptor HID | `core/tests/contract/` | every PR | Eng lead |
| E2E | Flujos críticos: pairing → segunda pantalla → inputs, con PC + celular físicos | `e2e/` (dispositivos en banco de pruebas) | nightly | QA |
| Performance | NFR-001 (latencia framebuffer), NFR-002 (input lag), NFR-005 (CPU), NFR-007 (batería) | `e2e/perf/` con captura óptica/Battery Historian | nightly / release | QA |
| Security | NFR-004: conexión sin token, replay, QR expirado | `e2e/security/` | release | Eng lead |
| Acceptance | Criterios AC-n de los REQ | `e2e/acceptance/` | pre-release | QA |

## 3. Coverage policy

> 💡 Coverage numbers are a symptom, not a goal. State what MUST have a test, rather than a global percentage.

| Artifact | Minimum |
|---|---|
| Every Approved SPEC | ≥ 1 TEST per flow, error and edge case |
| Every REQ AC-n | covered by at least one acceptance TEST |
| `core/` (protocolo, mapeo HID, validación de configs) | line coverage ≥ 80 % |
| Motor HID + captura | cubiertos por contract + integration (unit solo donde no requieren hardware) |

## 4. TEST ID convention

- Every suite/case has a stable `TEST-XXX` ID, defined in the test file and registered in `testing/traceability.md`.
- Naming: `TEST-XXX — verifies SPEC-YYY (aspect)` in the test file docstring/subject.
- One TEST may verify several aspects of one SPEC; a TEST spanning several SPECs is a smell → split it.
- TEST IDs are never reused; removed tests get a tombstone row in the traceability matrix.

## 5. Environments and data

| Environment | Purpose | Data |
|---|---|---|
| Local (dev) | developer loop: cargo test + unit Android | fixtures deterministas |
| CI | pre-merge gate: unit + integration (emulador Android) + contract | efímeros |
| Banco de pruebas | E2E/perf/security: PC Windows + Android 12 físico en LAN 802.11ac | perfiles de juego reales anonymized |

## 6. Gates

> 💡 What MUST pass for what to happen. Keep the list short and enforceable.

| Gate | Required for |
|---|---|
| Unit + Integration + Contract green | merge |
| E2E green (banco de pruebas) | release |
| Performance within NFR-001/002/005 thresholds | release |
| Security tests (NFR-004) green | release |
| Acceptance tests of affected REQs green | closing a REQ as Implemented |

## 7. SPEC ↔ TEST traceability

> 💡 The full matrix lives in `testing/traceability.md`. Rules:

- Every Approved SPEC has ≥ 1 TEST registered.
- Every REQ acceptance criterion maps to ≥ 1 TEST.
- A test that fails must point to exactly one of: bug in implementation (fix code), bug in test (fix test), or SPEC ambiguity (open CR). Silent adjustment of any of the three is forbidden.

## 8. Definition of Done

- [ ] Every test level has a location pattern and an owner.
- [ ] Gates are explicit and enforced in CI.
- [ ] Every Approved SPEC is covered by registered TESTs.
- [ ] Ambiguity resolution rule (fix code / fix test / open CR) is respected.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft adapted to Rust/Kotlin + hardware-in-the-loop |
