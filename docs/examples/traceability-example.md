# Example: a minimal traceability chain — Twinpad

> **What this is:** a worked, miniature example of the full chain `REQ → SPEC → DES → TEST → Implementation`, showing exactly what each document contains and how IDs connect them. Use it as the reference for tone and level of detail. It is an EXAMPLE — do not implement "QR pairing" from it; the real SPECs reserve their own IDs in `specs/_index.md`.

The fictional feature: **el celular se vincula al PC escaneando el QR** (REC-002 → REQ-002).

## The chain

```text
REQ-002 (Pairing PC–celular vía QR)           requirements/REQ-002-pairing-pc.md
  └─ SPEC-001 (Comportamiento del pairing)    specs/SPEC-001-pairing-qr.md
       ├─ DES-001 (Diseño del handshake)       design/DES-001-pairing-handshake.md
       │    └─ justified by ADR-004           decisions/ADR-004-qr-pairing.md
       └─ TEST-010 (Tests del pairing)         defined in test code
                                             registered in testing/traceability.md
```

## 1. REQ-002 — excerpt (`requirements/REQ-002-pairing-pc.md`)

```markdown
| ID | REQ-002 | Status | Approved | Priority | Must |
| Implemented by | SPEC-001 |

## 1. Objective
Conectar el celular debe ser un paso único y simple ("abres la app,
escaneas el QR, se vincula"), sin configuración manual de IPs ni puertos.

## 6. Acceptance criteria
- [ ] AC-1: Dado el servidor activo, cuando el usuario escanea el QR con la
      app Twinpad, entonces queda establecida una sesión autenticada.
- [ ] AC-2: Dado un dispositivo que no escaneó el QR, cuando intenta
      conectarse directamente, entonces es rechazado (NFR-004).
- [ ] AC-3: Dado un QR expirado, cuando se escanea, entonces la vinculación
      es rechazada con mensaje "QR expirado, generar uno nuevo".
```

> Note: WHAT and acceptance, not HOW. No criptografía específica, no payloads, no expiraciones concretas.

## 2. SPEC-001 — excerpt (`specs/SPEC-001-pairing-qr.md`)

```markdown
| ID | SPEC-001 | Implements | REQ-002 | Realized by | DES-001 | Verified by | TEST-010 |

## 4. Inputs
| Field | Type   | Constraints                | Required |
|-------|--------|----------------------------|----------|
| qr_payload | string | token URL-safe de 43 chars | yes      |
| device_name | string | 1–64 chars, UTF-8          | yes      |

## 9. Errors
| Condition                     | Observable behavior            | Code          |
|-------------------------------|--------------------------------|---------------|
| token expirado                | rechazo con mensaje de expiración | PAIR_EXPIRED  |
| token desconocido o reusado   | rechazo genérico tras ≥ 800 ms    | PAIR_INVALID  |
| servidor no disponible        | error de red en la app            | PAIR_UNREACHABLE |

## 11. Business rules
- R1: El token es de un solo uso; su consumo marca la sesión como
      establecida en la misma transacción del challenge mutuo.
- R2: Un intento con token inválido consume el mismo tiempo observable
      que uno válido (NFR-004).

## 13. Examples (GWT, normative)
Scenario: escaneo válido establece sesión
  Given un QR generado hace 10 segundos
  When el celular lo escanea y completa el challenge mutuo
  Then la sesión queda establecida y el QR no puede usarse de nuevo
```

> Note: exact behavior, enumerations, no code structure. La expiración y el challenge son comportamiento; cómo se implementa (HMAC, almacenamiento) es DES.

## 3. DES-001 — excerpt (`design/DES-001-pairing-handshake.md`)

```markdown
| ID | DES-001 | Realizes | SPEC-001 (full) | Depends on | ADR-004 |

## 1. Overview
Tokens opuestos aleatorios (ADR-004) guardados hasheados (SHA-256) en el
Servidor PC con TTL de 2 minutos. Módulo `server/pairing` (Rust) expone el
challenge; la app Android lo responde en `core` (protocolo compartido).

## 8. Error handling
| SPEC error    | Mechanism                                        |
|---------------|--------------------------------------------------|
| PAIR_INVALID  | constant-time compare of hash; artificial ≥ 800 ms delay |

## 12. Traceability
| SPEC aspect     | Where realized        | Test     |
|-----------------|-----------------------|----------|
| SPEC-001 R1     | server/pairing service | TEST-010 |
| SPEC-001 R2     | response shaping + delay | TEST-010 |
```

> Note: structure and mechanisms; the contract (inputs, errors) is cited, not redefined.

## 4. ADR-004 — excerpt (`decisions/ADR-004-qr-pairing.md`)

```markdown
## 4. Decision
Usaremos un QR con token de un solo uso seguido de challenge criptográfico
mutuo (no PIN ni cuentas en la nube).

## 6. Consequences — trade-offs accepted
- Dispositivos sin cámara no pueden vincularse por esta vía.
- Un QR visible y no expirado es vulnerable a terceros presentes físicamente.
```

## 5. TEST-010 — defined in code, registered in the matrix

```rust
// server/tests/pairing_test.rs
/**
 * TEST-010 — verifies SPEC-001 (main flow, R1, R2, errors PAIR_EXPIRED, PAIR_INVALID, PAIR_UNREACHABLE)
 */
```

Row in `testing/traceability.md`:

| REQ | SPEC | DES | TEST | Status | Notes |
|---|---|---|---|---|---|
| REQ-002 | SPEC-001 | DES-001 | TEST-010 | Implemented | full SPEC coverage |

## 6. Implementation

The PR description says:

```text
Implements: SPEC-001
Tests: TEST-010 (added), registered in testing/traceability.md
Docs: none modified (no CR needed)
```

## 7. What a change looks like

Mid-implementation the team discovers que el QR debe también caducar cuando
el servidor cambia de red (nueva IP). Eso es comportamiento nuevo → nadie
edita SPEC-001 silenciosamente. En su lugar:

1. Open `CR-002` quoting the gap, with the new rule text ready.
2. Impact: SPEC-001 (+R3), TEST-010 (+case), code (+1 hook), no REQ change.
3. Owner approves → SPEC-001 v1.1.0 (changelog: "R3 added, CR-002").
4. Matrix change-history row: `CR-002 → SPEC-001`.
5. Only then is the code change merged.
