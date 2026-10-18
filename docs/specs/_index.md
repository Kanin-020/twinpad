# Specifications Index — Twinpad

> Registry of all specifications. Content of each SPEC lives in its own file.

## Next free IDs

| Type | Next |
|---|---|
| SPEC | SPEC-009 |

> 💡 Reserve here when creating a Draft. Numbers are never reused.

## Candidate SPECs (from the interview definition, not yet drafted)

> 💡 These are suggested decompositions of REQ-001..008; reserve the ID when the Draft file is created.

| Candidate | Implements | Notes |
|---|---|---|
| Instalación y autoarranque del servidor PC | REQ-001 | Servicio/startup, indicador de estado |
| Generación y validez del QR de vinculación | REQ-002 | Contenido del QR, expiración |
| Handshake de sesión autenticada | REQ-002, NFR-004 | Challenge mutuo post-escaneo |
| Registro HID del celular como gamepad | REQ-003 | Aparición en el panel de controles del SO |
| Signature HID: mapeo de inputs (2 sticks, 10–12 botones, triggers, gyro) | REQ-003 | CTX-C5 |
| Feedback de vibración (rumble) hacia el celular | REQ-003 | PC → celular |
| Pipeline de captura y envío de framebuffers | REQ-004, REQ-005 | Captura en PC → transporte |
| Renderizado de la Segunda pantalla en Android | REQ-004 | Render + orientación |
| Transmisión y reproducción de audio | REQ-005 | Códec, sincronía A/V |
| Recuperación de streams tras corte de red | REQ-005 | Reanudación sin re-pairing |
| Detección de juego con SDK integrado | REQ-006 | Anuncio del juego al servidor |
| API del SDK Twinpad | REQ-006 | Contrato público del SDK |
| Formato y validación de Config externa | REQ-007 | JSON/DSL, carga y errores |
| Editor de superficie táctil | REQ-008 | Agregar/mover/asignar controles |
| Perfiles de configuración por juego | REQ-008 | Guardado y auto-carga |

## Active specifications

| ID | Title | Implements | Status | Realized by | Verified by | File |
|---|---|---|---|---|---|---|
| SPEC-001 | Pairing por QR y Handshake de sesión autenticada | REQ-002 | `Approved` | DES-001 | TEST-001..009 (5 en código `core/`) | [SPEC-001-pairing-session.md](SPEC-001-pairing-session.md) |
| SPEC-002 | App Android: escaneo del QR y pantalla de Pairing | REQ-002 | `Approved` | DES-002 | TEST-010..014 (implementados, 12 casos JVM) | [SPEC-002-android-pairing-ui.md](SPEC-002-android-pairing-ui.md) |
| SPEC-003 | Stream de video de la Segunda pantalla (framebuffers) | REQ-004, REQ-005 (video) | `Approved` | `-` | TEST-015..017 | [SPEC-003-video-stream.md](SPEC-003-video-stream.md) |
| SPEC-004 | Signature HID y flujo de inputs del Gamepad | REQ-003 | `Approved` | `-` | TEST-018, TEST-019 | [SPEC-004-signature-hid.md](SPEC-004-signature-hid.md) |
| SPEC-005 | Transmisión y reproducción de audio | REQ-005 | `Approved` | `-` | TEST-020 | [SPEC-005-audio-stream.md](SPEC-005-audio-stream.md) |
| SPEC-006 | Detección de juego con SDK Twinpad | REQ-006 | `Approved` | DES-004 | TEST-021, TEST-044, TEST-046, TEST-047 | [SPEC-006-plugin-sdk-detection.md](SPEC-006-plugin-sdk-detection.md) |
| SPEC-007 | Config externa para juegos no integrados | REQ-007 | `Draft` | `-` | TEST-022 | [SPEC-007-external-config.md](SPEC-007-external-config.md) |
| SPEC-008 | Superficie de controles táctiles configurables | REQ-008 | `Draft` | `-` | TEST-023 | [SPEC-008-touch-controls.md](SPEC-008-touch-controls.md) |

## Deprecated

| ID | Title | Superseded by | CR | File |
|---|---|---|---|---|
| — | — | — | — | — |

## Stats

| Metric | Value |
|---|---|
| Active SPECs | 7 |
| Approved SPECs without TEST | 0 |
| Approved SPECs | 5 |
| SPECs whose REQ is Deprecated | 0 |
| Last reviewed | 2026-10-07 |
