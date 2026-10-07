# TODO / Done — Twinpad

> Estado vivo del proyecto. Actualizado: 2026-10-07. Para agentes: los ítems siguen el pipeline SDD (REQ → SPEC → DES → implementación → verificación); no implementar sin SPEC `Approved` (workflow §2–3).

## ✅ Done

| # | Ítem | Detalle | Commit |
|---|---|---|---|
| 1 | Documentación SDD baseline | CTX, REQ-001..008, NFR-001..007, ARCH, ADR-001..006 (Accepted), ENG, workflow, templates, test-strategy, traceability | `4a969e4` |
| 2 | Scaffold Rust workspace | `core/` (sin I/O), `server/` + `capture/` + `config-loader/`, `sdk/`; lints ENG §4/§6; `Cargo.lock` | `1a22305` |
| 3 | Scaffold Android | módulos `app/` + `hid/` (minSdk 31, CTX-C1), Gradle wrapper 8.10.2 | `1a22305` |
| 4 | CI con gates | `.github/workflows/ci.yml`: cargo fmt/clippy/test + ktlint/detekt/testDebugUnitTest (workflow §8) | `1a22305` |
| 5 | SPEC-001 Draft → `Approved` v1.0.0 | Contrato QR `twinpad://v1`, mensajes NDJSON, HKDF/HMAC-SHA256, validez 300 s, R1–R7, E1–E5, GWT; OQ-1 (regen automática, R7) y OQ-2 (primitivas) resueltas; aprobado por el dueño | `94ce9a1` `3b0ae4d` `6e1c8b0` `3d266c2` |
| 6 | DES-001 `Approved` | Núcleo puro del protocolo: módulos `qr`/`message`/`crypto`/`handshake`; dependencias revisadas (ENG §11); adaptadores diferidos explícitos | `19ab7e0` |
| 7 | **Protocolo de sesión implementado en `core/`** | Codec QR (E5), mensajes NDJSON (E4), PairError E1–E5, HKDF-SHA256 + HMAC-SHA256 con verificación en tiempo constante, máquinas de estado servidor/cliente (R2 single-use, R3 300 s, timeout 10 s) | `feat(core)` |
| 8 | TEST-001/003/004/005/006 en código | 30 tests unitarios en verde (incluye vector RFC 5869 contra oráculo independiente); gates fmt/clippy/test verdes | `feat(core)` |
| 9 | README + TODO tracker | Comandos de build/gates verificados; este archivo | `9f2002f` `776f5af` |

## ⬜ TODO (pendiente)

| # | Ítem | Bloqueado por / dependencia | Dueño |
|---|---|---|---|
| 1 | **Adaptador TCP del Servidor PC** (`server/`): listener en LAN, registro de tokens/sesiones (mutex), genera QR y lo muestra; conecta `ServerHandshake` | SPEC-001 Approved ✓, DES-001 §12 | Eng/agent |
| 2 | **TEST-002 (E1), TEST-007 (reanudación), TEST-008 (revocación), TEST-009 (R7)** — integración en `server/tests/` en loopback TCP | Ítem 1 | Eng/agent |
| 3 | **App Android: escáner QR + pantalla de Pairing** con `ClientHandshake` vía FFI o re-implementación (REQ-002 AC-1/3) | Ítem 1; decisión DES sobre FFI (ENG §2) | Eng/agent |
| 4 | **SPEC-002+ drafts** — candidatos en `specs/_index.md`: Signature HID (REQ-003), pipeline framebuffer (REQ-004/005), SDK (REQ-006), Config externa (REQ-007), controles táctiles (REQ-008) | Pueden redactarse en Draft en paralelo | Eng lead + agent |
| 5 | **Aprobar documentos base en `Draft`**: CTX, REQ-001..008, NFRs, ARCH, ENG, test-strategy | Revisión del dueño | Eng lead (tú) |
| 6 | **`gradle.lockfile`** comprometido (ENG §11) cuando el proyecto Android tenga dependencias reales | Ítem 3 | Eng/agent |
| 7 | **CI remota en verde** — el workflow nunca corrió en GitHub (sin push); primer push valida el job Android | Push autorizado por ti | Eng lead (tú) |
| 8 | **Verificación NFR-001..007** (latencias, CPU, batería, seguridad NFR-004 end-to-end) — requieren banco de pruebas físico PC+Android | Release, no merge | QA/Eng lead |
| 9 | **Edge cases REQ-001 §8**: comportamiento tras suspensión/hibernación y cierre de sesión Windows → SPEC futuro | SPEC de REQ-001 | Eng lead |

## Camino mínimo actual

1 (adaptador server + QR) → 2 (TESTs de integración) → primer pairing de punta a punta en loopback TCP.
