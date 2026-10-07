# SPEC-XXX — <Nombre del comportamiento>

> ⚠️ TEMPLATE — replace every `<placeholder>`, delete guidance blocks (`> 💡 …`) and this note before `Approved`.

> **What this is:** the precise, testable behavior the system must exhibit for one capability. It is the contract that both tests and implementation must satisfy, and the reference an AI agent implements against.
> **What it is NOT:** a description of code structure (see `design/`) or of why the capability exists (see the REQ it implements).

| | |
|---|---|
| ID | SPEC-XXX |
| Title | `<name>` |
| Version | 0.1.0 |
| Status | `Draft / In review / Approved / Deprecated` |
| Owner | `<role>` |
| Implements | REQ-XXX |
| Realized by | DES-XXX (`-` until assigned) |
| Verified by | TEST-XXX (`-` until assigned) |
| Last updated | `<YYYY-MM-DD>` |

## 1. Purpose

> 💡 One line: what behavior this spec pins down and for which REQ.

<One or two sentences.>

## 2. Definitions

> 💡 Terms specific to this spec. Domain vocabulary lives in `product/context.md` §8 — reference, do not redefine (Twinpad, Segunda pantalla, Gamepad HID, Pairing, Framebuffer, Stream, Input táctil, Input físico, Signature HID, SDK Twinpad, Config externa, Plugin, Twinpad Core, Servidor PC).

- `<term>`: `<definition>` — or "See CTX glossary".

## 3. Preconditions

> 💡 State of the world that must hold before this behavior applies. If a precondition fails, behavior is undefined — or you must define it below.

- `<state>` (checked where: `<system boundary / component>`)

## 4. Inputs

> 💡 Full input contract. Enumerate; do not say "etc.".

| Field | Type | Constraints | Required |
|---|---|---|---|
| `<field>` | `<type>` | `<range / format / pattern>` | `<yes / no>` |

## 5. Outputs

> 💡 Observable results at the system boundary: data returned, state changes, emitted events, side effects. Enumerate all of them.

| Result | Value / shape | Condition |
|---|---|---|
| `<output>` | `<shape>` | `<when>` |

## 6. States

> 💡 If the feature has lifecycle states, define the machine here (ej: sesión: Desconectado → Emparejando → Autenticado → En sesión → Recuperando). Delete if stateless.

States: `<S1>`, `<S2>`, `<S3>`

| From | Event | To | Side effects |
|---|---|---|---|
| `<S1>` | `<event>` | `<S2>` | `<...>` |

Invariant: `<statement that must be true in every reachable state>`

## 7. Main flow

> 💡 The happy path, step by step, deterministic. Numbered steps, each observable.

1. `<step>`
2. `<step>`
3. `<result: output n>`

## 8. Alternative flows

> 💡 Every deviation from the happy path that still ends in success. One subsection per flow.

### 8.1 `<AF-1: name>`

- **Trigger:** `<event>`
- **Flow:** `<steps>`
- **Result:** `<output>`

## 9. Errors

> 💡 Every error the boundary can observe, with trigger and exact observable behavior. No error path may be "handled gracefully" without saying what that means.

| Condition | Observable behavior | Error code / message |
|---|---|---|
| `<trigger>` | `<what the caller observes>` | `<code>` |

## 10. Edge cases

> 💡 Boundary values, empty sets, maximums, concurrency, idempotency, ordering (ej: corte de red a mitad de frame, QR expirado, dos celulares escaneando el mismo QR). Each edge case must state the exact expected behavior — if you cannot, it is an open question, not an edge case.

| Case | Expected behavior |
|---|---|
| `<case>` | `<exact behavior>` |

## 11. Business rules

> 💡 Rules with IDs. Other docs reference `SPEC-XXX.R1`. Keep each rule atomic.

- **R1:** `<rule statement>`
- **R2:** `<rule statement>`

## 12. Contracts

> 💡 Wire-level contracts (protocolo de sesión, payload del QR, formato de Config externa, descriptor HID) — reference the canonical artifact. Do not paste a second copy here; pasting creates a second SSOT.

| Contract | Canonical source | Notes |
|---|---|---|
| `<API/event>` | `<path or URL to schema>` | `<versioning rule>` |

## 13. Examples (Given / When / Then)

> 💡 Concrete examples that double as acceptance-test seeds. These examples are normative: a test that contradicts an example means the TEST or the SPEC is wrong — report it.

```gherkin
Scenario: <name>
  Given <context>
  When <action>
  Then <observable outcome>
```

## 14. Invariants

- `<statement true after every operation>`

## 15. What "complies" means

> 💡 An implementation complies with this SPEC if and only if: all preconditions are honored, all flows produce the defined outputs, every error row is reproduced by its trigger, every edge case behaves as defined, all invariants hold, and all verification tests (§16) pass. Anything not defined here is NOT required — do not invent requirements.

<Keep this section verbatim or adapt consciously.>

## 16. Verification

> 💡 Every row here must appear in `testing/traceability.md`. A SPEC without verification rows cannot be `Approved`.

| Aspect | Verified by |
|---|---|
| Main flow | TEST-XXX |
| Error `<code>` | TEST-XXX |
| Edge case `<case>` | TEST-XXX |

## 17. Open questions

- `<question — owner — date — resolution>`

## Definition of Done

- [ ] Deterministic: two readers cannot reasonably interpret any statement differently.
- [ ] All flows, errors and edge cases enumerated — no "etc.", "and so on", "as appropriate".
- [ ] Every error and edge case has exact expected behavior.
- [ ] Inputs/outputs fully enumerated with types and constraints.
- [ ] No implementation detail (frameworks, file names, SQL, endpoints' internals) beyond the boundary contract.
- [ ] Every aspect has a TEST row; rows mirrored in traceability.md.
- [ ] No contradictions with the implementing REQ.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | `<YYYY-MM-DD>` | `<author>` | Initial draft |
