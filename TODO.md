# TODO / Done — Twinpad

> Estado vivo del proyecto. Actualizado: 2026-10-07. Para agentes: los ítems siguen el pipeline SDD (REQ → SPEC → DES → implementación → verificación); no implementar sin SPEC `Approved` (workflow §2–3).

## ✅ Done

| # | Ítem | Detalle | Commit |
|---|---|---|---|
| 1 | Documentación SDD baseline | CTX, REQ-001..008, NFR-001..007, ARCH, ADR-001..006 (Accepted), ENG, workflow, templates, test-strategy, traceability | `4a969e4` |
| 2 | Scaffold Rust workspace | `core/` (sin I/O), `server/` + `capture/` + `config-loader/`, `sdk/`; lints ENG §4/§6 (sin `unsafe`/`unwrap`/`todo`); `Cargo.lock` | `1a22305` |
| 3 | Scaffold Android | módulos `app/` + `hid/` (minSdk 31, CTX-C1), Gradle wrapper 8.10.2 | `1a22305` |
| 4 | CI con gates | `.github/workflows/ci.yml`: cargo fmt/clippy/test + ktlint/detekt/testDebugUnitTest (workflow §8) | `1a22305` |
| 5 | Gates verdes locales | `cargo fmt --check`, `clippy -D warnings`, `test` OK; `gradlew ktlintCheck detekt testDebugUnitTest` OK; `assembleDebug` → `app-debug.apk` (2.3 MB) | — |
| 6 | SPEC-001 (pairing QR + handshake) | Contrato QR `twinpad://v1#s=&p=&t=`, mensajes NDJSON `pair_request/challenge/proof/ack/error`, auth HKDF-SHA256 + HMAC-SHA256 (NFR-004/ADR-004), validez QR 300 s, reglas R1–R7, errores E1–E5, 6 escenarios GWT | `94ce9a1` |
| 7 | OQ-1 resuelta | Regeneración automática del QR al expirar (R7) + TEST-009 reservado | `3b0ae4d` |
| 8 | OQ-2 resuelta | Primitivas criptográficas definitivas (confirmación del dueño) | `6e1c8b0` |
| 9 | SPEC-001 `In review` v0.3.0 | Sin preguntas abiertas; índice y trazabilidad sincronizados (TEST-001..009 reservados, sin código) | `6e1c8b0` |
| 10 | README | Requisitos, comandos de build/gates verificados, ruta del APK, estructura | `9f2002f` |

## ⬜ TODO (pendiente)

| # | Ítem | Bloqueado por / dependencia | Dueño |
|---|---|---|---|
| 1 | **Aprobar SPEC-001** (`In review` → `Approved`) — desbloquea toda la implementación | Revisión del dueño; cero open questions | Eng lead (tú) |
| 2 | **DES-001** — diseño detallado del protocolo de sesión (realiza SPEC-001) | SPEC-001 `Approved` | Eng |
| 3 | **Implementar protocolo de sesión en `core/`** (tipos, encode/decode QR, mensajes NDJSON, HKDF/HMAC) + crates `hmac`/`sha2`/`hkdf` (revisión de dependencias ENG §11) | Ítems 1–2 | Eng/agent |
| 4 | **TEST-001..009 en código** (protocolo en loopback, sin mockear: test-strategy §10) + actualizar traceability a estado real | Ítem 3 | Eng/agent |
| 5 | **Servidor PC mínimo**: binario `twinpad-server` escuchando en LAN + generación/render del QR (REQ-001 AC-1/2, SPEC-001 §7.1) | Ítems 3–4; decide también: mecanismo de autoarranque (ARCH §11 OQ abierta: servicio Windows vs startup) | Eng/agent |
| 6 | **App Android: escáner QR + pantalla de Pairing** (REQ-002 AC-1/3; UI según SPEC-001 §9 mensajes) | Ítems 3–5 | Eng/agent |
| 7 | **SPEC-002+ drafts** — candidatos en `specs/_index.md`: Signature HID (REQ-003), pipeline framebuffer (REQ-004/005), SDK (REQ-006), Config externa (REQ-007), controles táctiles (REQ-008) | Ninguno (pueden redactarse en Draft en paralelo) | Eng lead + agent |
| 8 | **Aprobar documentos base en `Draft`**: CTX, REQ-001..008, NFRs, ARCH, ENG, test-strategy (solo lo `Approved` es vinculante) | Revisión del dueño | Eng lead (tú) |
| 9 | **`gradle.lockfile`** comprometido (ENG §11) — generarlo cuando el proyecto Android tenga dependencias reales | Ítem 6 | Eng/agent |
| 10 | **CI remota en verde** — el workflow nunca corrió en GitHub (sin push); primer push valida el job Android | Push autorizado por ti | Eng lead (tú) |
| 11 | **Verificación NFR-001..007** (latencias, CPU, batería, seguridad): requieren banco de pruebas físico PC+Android | Release, no merge | QA/Eng lead |
| 12 | **Edge cases REQ sin definir**: REQ-001 §8 (comportamiento tras suspensión/hibernación y cierre de sesión Windows) → SPECs futuros | SPECs que implementen REQ-001 | Eng lead |

## Criterios del próximo paso

1 → 2 → 3 → 4: camino mínimo del workflow §2 para el primer comportamiento real (pairing de punta a punta en `core/`).
