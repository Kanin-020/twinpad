# Engineering Guidelines — Twinpad

> ⚠️ TEMPLATE — replace every `<placeholder>`, delete guidance blocks (`> 💡 …`) before `Approved`.

> **What this is:** the transversal rules for writing code in Twinpad: style, structure, typing, errors, logging, security, testing, dependencies — plus specific rules for AI agents writing code here.
> **What it is NOT:** project-specific decisions (ADRs own those) or behavior (SPECs own that). Guidelines apply to ALL code; they never restate a SPEC.

| | |
|---|---|
| ID | ENG |
| Version | 0.1.0 |
| Status | Draft |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |

## 1. Language and runtime

| Aspect | Decision |
|---|---|
| Android app | Kotlin (JVM 17 toolchain), minSdk 31 / Android 12 (CTX-C1) |
| Critical paths (PC + Android) | Rust (stable), via JNI/NDK en Android (ADR-001) |
| PC server | Rust (Servidor PC + Twinpad Core), Windows primero |
| Package manager | Gradle (Android), Cargo (Rust) |
| ADR | ADR-001, ADR-002 |

## 2. Project structure

> 💡 One convention for where things live. Keep it short — the structure itself is documentation.

```text
android/            app Twinpad (Kotlin)
  app/              UI, pairing QR, superficie de controles (REQ-008)
  hid/              Motor HID (Kotlin ↔ Rust FFI) (REQ-003)
core/               crate Rust compartido: protocolo de sesión, tipos
server/             crate Rust: Servidor PC + Twinpad Core (REQ-001)
  capture/          Captura de framebuffer/audio (REQ-004, REQ-005)
  config-loader/    Cargador de Config externa (REQ-007)
sdk/                SDK Twinpad (REQ-006): binding para juegos (Rust + wrapper)
```

Rules:

- La frontera Kotlin ↔ Rust es solo FFI declarada en `hid/` y el wrapper del `sdk/`; ningún otro módulo Kotlin llama a JNI.
- Los crates Rust no dependen de Android ni de Windows salvo los módulos de plataforma (`capture/`, `hid/`).
- Sin lógica de negocio en handlers de UI o de red; la lógica vive en `core/`.

## 3. Naming

| Element | Convention | Example |
|---|---|---|
| Kotlin files | PascalCase | `PairingScreen.kt` |
| Kotlin types | PascalCase | `SessionManager` |
| Kotlin functions/vars | camelCase | `startSession()` |
| Kotlin constants | SCREAMING_SNAKE | `MAX_TOUCH_CONTROLS` |
| Rust crates/modules | snake_case | `config_loader` |
| Rust types | PascalCase | `Framebuffer` |
| Rust functions | snake_case | `encode_frame()` |
| Constants (Rust) | SCREAMING_SNAKE | `HID_REPORT_DESCRIPTOR` |
| Tests | mirror source + `_test`/`Test` | `session_test.rs`, `PairingScreenTest.kt` |

## 4. Typing and contracts

> 💡 Rules that make code verifiable: no implicit any equivalent, public functions typed, prefer types from the canonical schema sources.

- Kotlin: sin tipos platform sin validar (anotar `@Nullable/@NotNull` en fronteras FFI); `sealed` para estados de sesión.
- Rust: `#![deny(unsafe_code)]` fuera de los módulos FFI; tipos de dominio en `core/` compartidos por ambos lados.
- Toda entrada externa (payload QR, Config externa, red) se valida en la frontera antes de cruzar a `core/`.

## 5. Core code pautas (normative)

> These 18 pautas are binding for ALL code in this repository — human or AI written. Each has a verification method; a reviewer, lint rule or CI gate may reject code that violates them. Thresholds are defaults, adjustable via CR.

| # | Pauta | Threshold / rule | Verification |
|---|---|---|---|
| P1 | Use descriptive names for variables, functions, classes and modules | Name communicates domain meaning (usar glosario CTX §8) | Review + naming table §3 |
| P2 | Prefer names that explain what a datum represents, not its type | No Hungarian: `activeSession`, not `sessionObj` | Review |
| P3 | Keep functions small, one clear responsibility | ≤ 40 lines body, one job | Clippy + Kotlin lint + review |
| P4 | Avoid functions with too many parameters | ≤ 3 positional; more → struct/options object | Clippy `too_many_arguments` + review |
| P5 | Avoid deep nesting | ≤ 3 levels; guard clauses / early returns | Clippy + review |
| P6 | Reduce cyclomatic complexity | ≤ 10 branches per function | Clippy `cyclomatic_complexity` |
| P7 | Eliminate duplicated code | No copy-pasted logic blocks (≥ 6 similar lines); extract a shared helper in `core/` | jscpd/cargo-dup en CI + review |
| P8 | Avoid global variables | No `static mut`, no singletons mutables fuera de bootstrap declarado | Clippy + review |
| P9 | Minimize mutable state | Prefer inmutabilidad; mutación local y justificada (streams en canales) | Review |
| P10 | Keep functions deterministic when possible | Same input → same output; encode/decode y mapeo táctil→HID deterministas | Unit tests con fixtures fijos |
| P11 | Avoid hidden side effects | Efectos solo en fronteras declaradas (red, HID, captura, vibración); `core/` sin I/O | Import-boundary check en CI |
| P12 | Use named constants for meaningful values | Sin números mágicos de dominio (latencias, tamaños de cola) | Clippy + review |
| P13 | Avoid magic numbers and strings | Igual que P12; strings de UI en recursos Android (i18n-ready) | TEST scan |
| P14 | Keep formatting consistent | `cargo fmt` + `ktlint`, una config compartida | CI (format check) |
| P15 | Eliminate dead code | No exports/branches sin uso | Clippy `dead_code` (deny), Kotlin lint |
| P16 | Never keep commented-out code "just in case" | Delete it; version control is the history | Review + CI grep |
| P17 | Avoid premature abstractions | Abstraction requires ≥ 2 concrete uses or a DES mandate | Review |
| P18 | Prefer simple code over clever code | Meta-programming/exotics requiere justificación en DES/ADR | Review |

### 5.1 SOLID principles (normative)

> Applied at module scale (ENG §2 structure). DRY is enforced by P7; SOLID refines the same discipline for modules, interfaces and dependencies. All five are binding and verifiable.

| # | Principle | Normative rule | Verification |
|---|---|---|---|
| S1 | Single Responsibility | Cada módulo/crate tiene una sola razón de cambio (p.ej. `capture/` no sabe de HID) | Review (con P3/P4) |
| S2 | Open/Closed | Nuevos juegos/plugins se agregan por registro/config, no editando cadenas de `switch`/`match` existentes | Review + structure tests |
| S3 | Liskov Substitution | Toda implementación de un trait/interface honra el contrato completo del SPEC correspondiente | Contract tests + review |
| S4 | Interface Segregation | Traits/interfazas angostos: el Motor HID no expone streaming; el stream no expone HID | Review; lint de parámetros sin uso |
| S5 | Dependency Inversion | `core/` no importa I/O (red, HID, captura, DOM); depende de puertos inyectados que implementan los módulos de plataforma | Import-boundary check en CI |

## 6. Error handling

> 💡 One philosophy, then mechanics. Observable error behavior comes from SPECs — this section only defines the mechanism, not when errors happen.

- Rust: `Result<T, Error>` tipado en toda operación fallible; sin `unwrap()`/`expect()` en código de producción (permitidos solo en tests y bootstrap).
- Kotlin: estados de sesión modelados como sealed classes; sin excepciones cruzando la frontera FFI (se traducen a tipos `Result`/estado).
- Errores observables (códigos de SPEC §9) se definen una sola vez en `core/` y se traducen a UI/mensajes en las fronteras.

## 7. Logging

| Rule | Value |
|---|---|
| Format | estructurado (tracing en Rust; logger JSON en Kotlin) con session-id de correlación |
| Levels | `error`: fallos de sesión/protocolo; `warn`: recuperación (reconexion, jitter); `info`: pairing/estado de sesión; `debug`: métricas de stream; `trace`: deshabilitado en release |
| Prohibited | Tokens de sesión, contenido de pantalla, IPs de usuarios, payloads completos |

## 8. Security

- Validación de toda entrada de red y de Config externa en la frontera (parse estricto, sin fallback permisivo).
- El token de sesión (ADR-004) solo existe en memoria cifrada; jamás en logs ni en archivos planos.
- Permisos Android mínimos: BT, cámara (solo para escanear QR), vibración, foreground service; sin almacenamiento amplio.

## 9. Performance

> 💡 Only transversal rules. Specific thresholds live in NFRs.

- Hot paths (captura, encode, transporte, HID) sin alocaciones por frame en Rust donde el DES lo indique.
- Los streams priorizan: inputs > video > audio (ARCH §8); no bloquear la cola de inputs por video.
- Sin I/O síncrona en el hilo de UI de Android ni en el hilo de render.

## 10. Testing rules

> 💡 Where tests live, naming, what must have a test, what must NOT be mocked. Full strategy: `testing/test-strategy.md` (reference, do not duplicate).

- Tests unitarios Rust en el mismo crate (`#[cfg(test)]`), Kotlin en `src/test/`; integración marcada `#[ignore]`/`@Tag("integration")` en CI nocturno.
- Un TEST ID por suite, registrado en `testing/traceability.md`.
- Prohibido mockear el protocolo de sesión en tests de contrato (usar pares reales core↔client en loopback).

## 11. Dependencies

| Rule | Value |
|---|---|
| Adding a dependency | Rust: revisión de crate (mantenimiento, `unsafe`, licencia); Kotlin: revisión de permisos y tamaño |
| Versioning policy | `Cargo.lock` + `gradle.lock` comprometidos; actualizaciones programadas (mensual) |
| Prohibited | Licencias GPL en binarios distribuidos (v1); crates sin mantenimiento > 12 meses para hot paths |

## 12. Code style

> 💡 Mechanize everything possible; humans and agents should never argue about formatting.

- Formatter: `cargo fmt` (Rust), `ktlintFormat` (Kotlin)
- Linter: `clippy -- -D warnings` (Rust), `detekt` + `ktlint` (Kotlin)
- Formatting is enforced in CI: yes

## 13. Rules for AI agents writing code

> 💡 The rules a code-generating agent must follow in THIS repo, beyond `process/ai-agent-rules.md`. Keep short and checkable; pair with AGENTS.md at the repo root.

1. Read the SPEC first; implement exactly it — never invent behavior.
2. Respect architecture boundaries (architecture.md §3) and structure (§2 here): `core/` sin I/O; FFI solo en módulos declarados.
3. Every new test carries a TEST ID and is registered in `testing/traceability.md`.
4. Never modify specs, REQs or architecture to make code work — open a CR instead.
5. No placeholders, no `TODO` in delivered code, no commented-out code.
6. On ambiguity or contradiction: STOP and report, referencing both IDs.
7. No `unsafe` fuera de los módulos FFI declarados; cualquier excepción requiere nota en el PR y justificación en DES.

## 14. Definition of Done

- [ ] Formatter and linter configured and enforced.
- [ ] Naming table complete and consistent.
- [ ] Error-handling philosophy stated in one line.
- [ ] AI rules are checkable (each rule a reviewer or CI can verify).
- [ ] Nothing here duplicates a SPEC, an ADR decision or the test strategy — links only.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft adapted to Kotlin/Rust stack |
