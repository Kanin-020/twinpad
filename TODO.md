# TODO / Done — Twinpad

> Estado vivo del proyecto. Actualizado: 2026-10-08. Para agentes: los ítems siguen el pipeline SDD (REQ → SPEC → DES → implementación → verificación); no implementar sin SPEC `Approved` (workflow §2–3). Registrado en el mapa de documentación: [`docs/README.md`](docs/README.md) ("How to use this documentation").

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
| 10 | **App Android de Pairing (SPEC-002/DES-002)** | Máquina de estados pura U1–U5, handshake Kotlin (HKDF+HMAC paridad con core), cliente TCP con timeouts E1, UI Compose con strings canónicas §9; TEST-010..014 = 12 tests JVM verdes; APK debug generado | `feat(android)` |
| 11 | **Scripts de build/test/instalación** | `scripts/build.sh`, `test.sh` (6 gates §8), `install-apk.sh` (adb), `build-apk.sh [debug\|release]` — todos verificados ejecutándolos; release firmado con debug key para pruebas | `702028f` `13c8dbd` |
| 12 | **Adaptador TCP del Servidor PC (REQ-001/002)** | `server::tokens` (R2/R3/R6/R7, RNG/reloj inyectados), `server::session` (bucle TCP con timeouts §9), binario `twinpad-server` (listener LAN, R7 automático); 7 tests de integración en loopback + 6 unitarios; verificado punta a punta: pair_ack + E3 al reintentar token consumido | `feat(server)` |
| 13 | **QR renderizado + decodificador de cámara** | Server: QR Unicode en terminal (`server::qr_render`, qrcodegen MIT/Apache). App: analyzer ML Kit en `PairingCameraController` (SPEC-002 §6 U2, diferido de DES-002 §14 ya resuelto) | `feat(server)` `feat(android)` |
| 14 | **Cadena de conexión verificada de punta a punta** | Fixes de la primera ronda de pruebas: preview de cámara no cableado a la Activity (salía negro); carrera Connect/SendLine en `TcpPairingClient` descartaba el `pair_request` (E1 siempre); polaridad invertida del QR terminal recortaba el lado izquierdo. Permiso INTERNET explícito; scripts `run-server.sh` / `test-qr.sh` | `fix(android)` `fix(server)` `build(scripts)` |
| 15 | **Conexión persistente (SPEC-001 §8.2)** | `SessionRegistry` retiene cada stream tras el pair_ack; hilo de conexión queda en bucle de lectura (EOF libera la sesión). Verificado con cliente de prueba: conexión viva 5 s tras pair_ack; ganchos para TEST-007/008 | `feat(server)` |
| 16 | **SPEC-003 (video) Approved + pipeline implementado** | Transporte de framebuffers rgb8 sin compresión sobre la conexión persistente: codec en `core::stream` (40 tests core), emisor en `server::video` (start/frames/stop, 30 fps), receptor en la app (`StreamCodec` + `FrameRenderer` + `StreamStateMachine` V0/V1/V2, vista Compose). Verificado: 100 frames `fn=0..99` 320×180 por TCP real — **la app necesita prueba en tu teléfono** | `feat(video)` |
| 17 | **Prueba física de pairing + video: 3 problemas resueltos** | 1) La "pantalla arcoíris" era el patrón demo (no bug de render; confirmado por el owner). 2) El corte a los ~30 s era el stream demo limitado a 900 frames → ahora emite mientras la sesión viva. 3) Sirvió de banco físico real: QR → pairing → stream visibles en el teléfono | `ef4f3ac` (parcial) |
| 18 | **SPEC-004 (HID) `Approved` v1.0.0 + ADR-007 + inputs implementados** | Owner aprobó el SPEC y la OQ de transporte: `input_report` viaja por la sesión LAN de SPEC-001 (ADR-007); BT HID nativo queda como meta (ADR-003). Codec estricto en `core::input` (E4, gyro opcional), `GamepadStateMachine` (seq creciente, reporte solo al cambio) + `InputCodec` + superficie táctil (D-pad + A/B/X/Y con presionar/soltar) en la app, filtro R2 de `seq` en el server. TEST-018 (parcial) | `e773c55` `ef4f3ac` `7c9ce01` |
| 19 | **Duplicado del escritorio PC como fuente del stream** | Decisión del owner (SPEC-003 §17): `twinpad-capture::DesktopGrabber` con xcap + conversión pura RGBA→rgb8 escalada a 640×360 (vecino más próximo, 4 tests); `run_frame_stream` genérico con re-anuncio §8.1; captura por defecto con fallback al patrón demo (`TWINPAD_SOURCE=demo`); aviso de limitación Wayland | `65cea4a` `597e91f` |

## ⬜ Qué sigue (orden propuesto)

| # | Ítem | Bloqueado por / dependencia | Dueño |
|---|---|---|---|
| 1 | **Sticks analógicos + triggers en la superficie táctil** — la Signature ya los define (SPEC-004 §2); falta el UI (pad virtual + sliders) que los alimente | Nada: SPEC-004 Approved | Eng/agent |
| 2 | **Prueba física de la Segunda pantalla real** — duplicado del escritorio visible en el teléfono + inputs llegando al server (logs `input_report aceptado`) | Server reconstruido (`65cea4a`); en Wayland puede pedir permiso del portal | Tú (banco físico) |
| 3 | **Aprobar SPEC-005 (audio)** → DES → implementación (`audio_start/chunk/stop`, jitter 500 ms, sincronía A/V) | Draft pendiente de tu revisión | Tú aprueba → Eng/agent |
| 4 | **Aprobar SPEC-006 (Plugin/SDK)** — detección de juego + anuncio `game_id/capabilities`; es la fuente de contenido real de la Segunda pantalla | Draft pendiente de tu revisión | Tú aprueba → Eng/agent |
| 5 | **SPEC-008: editor de superficie táctil configurable** (≤24 controles, perfiles por juego) | Draft; base: ítem 1 + SPEC-007 (Config) | Tú aprueba → Eng/agent |
| 6 | **BT HID nativo (ADR-003)** — que Windows liste el celular como gamepad (REQ-003 AC-1) + rumble PC→app (TEST-019); requiere DES-004 | Depende de soporte HID device role del teléfono (CTX-A2); la app ya manda inputs por LAN mientras tanto | Eng lead + tú |
| 7 | **TEST-007 (reanudación §8.2) y TEST-008 (revocación por UI)** sobre el registro de sesiones ya implementado | Nada técnico; prioridad baja frente a features | Eng/agent |
| 8 | **Infra de release**: keystore propio para APK release, `gradle.lockfile`, primer push que valide CI remota | Push autorizado por ti (CI); keystore antes de distribuir | Tú + Eng/agent |
| 9 | **Verificación NFR-001..007** (latencias p95, CPU, batería, NFR-004 end-to-end) — requieren banco físico + inyección sintética | Release, no merge | QA/Eng lead |

## Camino actual (lo próximo que se construye)

1. **Ítem 1**: sticks analógicos + triggers táctiles en la app (SPEC-004 ya lo cubre; no requiere SPEC nuevo).
2. **Ítem 2**: tú prueban en el teléfono el duplicado de escritorio y los botones (el server ya loguea los `input_report`).
3. **Ítem 3**: tú revisas/apruebas SPEC-005 (audio) para seguir el pipeline.

## Pendientes de tu decisión (Drafts por aprobar)

| Documento | Qué desbloquea al aprobarse |
|---|---|
| SPEC-005 (audio) | Ítem 3 — sonido del juego en el teléfono |
| SPEC-006 (Plugin/SDK) | Ítem 4 — contenido real del juego en la Segunda pantalla |
| SPEC-007 (Config externa) | Ítem 5 — controles para juegos sin integración |
| SPEC-008 (superficie táctil) | Ítem 5 — editor de botones configurable |
| CTX, REQ-001..008, NFRs, ARCH, ENG, test-strategy (base en `Draft`) | Formalidad del pipeline SDD; no bloquean implementación |
