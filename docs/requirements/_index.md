# Requirements Index — Twinpad

> Registry of all requirements. This file is the counter and the map — the content of each requirement lives in its own file.

## Next free IDs

| Type | Next |
|---|---|
| REQ | REQ-009 |
| NFR | NFR-008 |

> 💡 Reserve here when creating a Draft: add a row with Status `Draft` and the intended owner. Numbers are never reused, even if the draft is discarded (mark it `Deprecated`).

## Active requirements

| ID | Title | Priority | Status | Implemented by | File |
|---|---|---|---|---|---|
| REQ-001 | Servidor PC persistente y empaquetado | Must | `Draft` | - | [REQ-001-pc-server-standalone.md](REQ-001-pc-server-standalone.md) |
| REQ-002 | Pairing PC–celular vía QR | Must | `Draft` | - | [REQ-002-pairing-pc.md](REQ-002-pairing-pc.md) |
| REQ-003 | Emulación Gamepad Bluetooth HID nativo | Must | `Draft` | - | [REQ-003-bluetooth-hid.md](REQ-003-bluetooth-hid.md) |
| REQ-004 | Segunda pantalla vía framebuffer al celular | Must | `Draft` | - | [REQ-004-second-screen-video.md](REQ-004-second-screen-video.md) |
| REQ-005 | Transmisión de video y audio | Must | `Draft` | - | [REQ-005-stream-video-audio.md](REQ-005-stream-video-audio.md) |
| REQ-006 | Integración vía SDK Twinpad (plugin modelo B) | Must | `Draft` | - | [REQ-006-plugin-sdk.md](REQ-006-plugin-sdk.md) |
| REQ-007 | Config externa para juegos no integrados (plugin modelo C) | Should | `Draft` | - | [REQ-007-plugin-config.md](REQ-007-plugin-config.md) |
| REQ-008 | Controles táctiles configurables en la app | Should | `Draft` | - | [REQ-008-touch-controls.md](REQ-008-touch-controls.md) |

## Non-functional requirements

| ID | Title | Status | File |
|---|---|---|---|
| NFR-001 | Latencia de la Segunda pantalla (framebuffer) | `Draft` | [nfrs.md](nfrs.md) |
| NFR-002 | Latencia de input HID (botón → juego) | `Draft` | [nfrs.md](nfrs.md) |
| NFR-003 | Servidor PC persistente y de bajo consumo | `Draft` | [nfrs.md](nfrs.md) |
| NFR-004 | Seguridad de la sesión de Pairing | `Draft` | [nfrs.md](nfrs.md) |
| NFR-005 | Utilización de CPU del renderizado en Android | `Draft` | [nfrs.md](nfrs.md) |
| NFR-006 | Múltiples sesiones simultáneas | `Draft` | [nfrs.md](nfrs.md) |
| NFR-007 | Eficiencia energética del Android | `Draft` | [nfrs.md](nfrs.md) |

## Deprecated

| ID | Title | Superseded by | CR | File |
|---|---|---|---|---|
| — | — | — | — | — |

## Stats

| Metric | Value |
|---|---|
| Active REQs | 8 |
| Approved REQs without implementing SPEC | 0 |
| Last reviewed | 2026-10-07 |
