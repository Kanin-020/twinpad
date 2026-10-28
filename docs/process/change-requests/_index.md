# Change Requests Index — Twinpad

## Next free IDs

| Type | Next |
|---|---|
| CR | CR-033 |

## Open / In review

| ID | Title | Targets | Status | Author | File |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

> CR-009 y CR-010 fueron aprobados por el Product owner; CR-009 se aplicó en su iteración. CR-010 está implementado en código y contratos; permanece abierto únicamente el gate físico Cemu/Android de ±150 ms A/V y hasta 60 fps.

## Approved / in execution

| ID | Title | Targets | Decided | File |
|---|---|---|---|---|
| CR-024 | Corrección de ADR-003: HID nativo diferido, sin giroscopio comprometido (aplicado en ADR-003 1.1.0) | ADR-003, decisions/_index.md | 2026-10-27 | [CR-024-adr-003-hid-deferred.md](CR-024-adr-003-hid-deferred.md) |
| CR-023 | Vía de giroscopio para terceros: SDK principal, Config externa sin movimiento, HID diferido (aplicado en REQ-007 0.2.0, SPEC-004 1.8.0, SPEC-006 1.14.0) | REQ-007, SPEC-004, SPEC-006 | 2026-10-27 | [CR-023-third-party-motion-via-sdk.md](CR-023-third-party-motion-via-sdk.md) |
| CR-022 | Un único mapeo de ejes AndroidDSU (en Android) y ruta Cemu idéntica a DSU | SPEC-004, DES-002/004 | 2026-10-26 | [CR-022-motion-axis-mapping-single-source.md](CR-022-motion-axis-mapping-single-source.md) |
| CR-021 | Alimentar la fusión VPAD con muestras de sensores AndroidDSU | SPEC-004, DES-002/004 | 2026-10-24 | [CR-021-androiddsu-motion-fusion.md](CR-021-androiddsu-motion-fusion.md) |
| CR-020 | Aceleración por hardware del encoder H.264 de Cemu | SPEC-003, ARCH, DES-004 | 2026-10-23 | [CR-020-hardware-video-encoding.md](CR-020-hardware-video-encoding.md) |
| CR-019 | Reducir al 50% la sensibilidad gyro de Cemu en VPAD | SPEC-004, DES-004 (superseded by CR-021) | 2026-10-22 | [CR-019-cemu-gyro-sensitivity.md](CR-019-cemu-gyro-sensitivity.md) |
| CR-018 | Alinear valores y colores de los monitores gyro | SPEC-004, DES-002/004 | 2026-10-22 | [CR-018-align-gyro-monitors.md](CR-018-align-gyro-monitors.md) |
| CR-017 | Mover el monitor gyro de Cemu a una ventana externa ampliada | SPEC-004/006, DES-004 | 2026-10-22 | [CR-017-cemu-external-gyro-monitor.md](CR-017-cemu-external-gyro-monitor.md) |
| CR-016 | Monitores opcionales de orientación gyro en Android y Cemu | SPEC-002/004/006, DES-002/004 | 2026-10-22 | [CR-016-gyro-monitor.md](CR-016-gyro-monitor.md) |
|---|---|---|---|---|
| CR-015 | Añadir metadata de access units H.264 al IPC Plugin | SPEC-006, DES-004 | 2026-10-20 | [CR-015-game-frame-h264-metadata.md](CR-015-game-frame-h264-metadata.md) |
| CR-014 | Migrar la Segunda pantalla de JPEG/tiles a H.264/AVC con canal media seguro | SPEC-003, ARCH, DES-003/004 | 2026-10-20 | [CR-014-video-codec-migration.md](CR-014-video-codec-migration.md) |
| CR-011 | Landscape habitual inmersivo y ejes gyro relativos a pantalla | SPEC-002, SPEC-004, DES-002 | 2026-10-18 | [CR-011-android-landscape-immersive-gyro.md](CR-011-android-landscape-immersive-gyro.md) |
| CR-013 | VPAD temporal automático de Jugador 1 en Cemu | SPEC-006, DES-004 | 2026-10-19 | [CR-013-cemu-temporary-vpad-player-one.md](CR-013-cemu-temporary-vpad-player-one.md) |## Applied

| ID | Title | Targets | Decided | File |
|---|---|---|---|---|
| CR-032 | Switch «Distribución Nintendo» en el drawer: botones de cara táctiles y mando físico (aplicado en SPEC-002/SPEC-008 y código Android) | SPEC-002, SPEC-008 | 2026-10-28 | [CR-032-nintendo-button-layout.md](CR-032-nintendo-button-layout.md) |
| CR-031 | Ajustes por defecto: 1080p, escalado contener, bitrate máximo, HUD fps y botones visibles (aplicado en SPEC-002 y código Android) | SPEC-002 | 2026-10-28 | [CR-031-default-stream-settings.md](CR-031-default-stream-settings.md) |
| CR-030 | Resolución efectiva del GamePad: `video_config` reenvía w/h, escala del DRC antes del encode (aplicado en SPEC-002/003/006 y código server/Cemu) | SPEC-002, SPEC-003, SPEC-006 | 2026-10-28 | [CR-030-effective-gamepad-resolution.md](CR-030-effective-gamepad-resolution.md) |
| CR-029 | FPS unlimited: sin tope 30/60 ni pacing por fps; `stream_start.fps` como metadata (aplicado en SPEC-002/003/006, core, server, Cemu y Android) | SPEC-002, SPEC-003, SPEC-006 | 2026-10-28 | [CR-029-unlimited-fps.md](CR-029-unlimited-fps.md) |
| CR-028 | Mando físico conectado al teléfono: mapper y fusión con la superficie táctil (aplicado en SPEC-004 y código Android) | SPEC-004 | 2026-10-27 | [CR-028-physical-gamepad-input.md](CR-028-physical-gamepad-input.md) |
| CR-027 | Bitrate objetivo del encoder H.264 configurable: drawer, env y reenvío a Cemu (aplicado en SPEC-002 y SPEC-003) | SPEC-002, SPEC-003 | 2026-10-27 | [CR-027-video-bitrate-config.md](CR-027-video-bitrate-config.md) |
| CR-026 | Pantalla limpia durante la transmisión: sin marco de controles ni textos superpuestos (aplicado en SPEC-002 y SPEC-003) | SPEC-002, SPEC-003 | 2026-10-27 | [CR-026-clean-overlay-during-stream.md](CR-026-clean-overlay-during-stream.md) |
| CR-025 | Modo de escalado de la Segunda pantalla contain/expand y mapeo táctil sobre la imagen visible (aplicado en SPEC-002 y SPEC-003) | SPEC-002, SPEC-003, DES-002 | 2026-10-27 | [CR-025-video-fit-contain-expand.md](CR-025-video-fit-contain-expand.md) |
 | Stream de video v1 con compresión JPEG (`fmt=jpeg`) | SPEC-003 | 2026-10-08 | [CR-001-video-jpeg-compression.md](CR-001-video-jpeg-compression.md) |
| CR-002 | Actualizaciones parciales del Stream de video por tiles | SPEC-003 | 2026-10-16 | [CR-002-delta-video-tiles.md](CR-002-delta-video-tiles.md) |
| CR-003 | PCM de audio del juego vía SDK: canal IPC separado, callback y prioridad video/drop-old | SPEC-005, SPEC-006, REQ-006 | 2026-10-17 | [CR-003-sdk-audio-pcm.md](CR-003-sdk-audio-pcm.md) |
| CR-004 | Adaptador experimental Cemu para prueba semi-real de Twinpad | CTX, ARCH, SPEC-006 | 2026-10-17 | [CR-004-cemu-experimental-integration.md](CR-004-cemu-experimental-integration.md) |
| CR-005 | Flujo experimental Cemu: conexión, DRC e inputs | SPEC-004/006, ARCH, DES-004 | 2026-10-18 | [CR-005-cemu-automatic-drc-and-input.md](CR-005-cemu-automatic-drc-and-input.md) |
| CR-006 | Conexión Twinpad de Cemu configurable | SPEC-006, ARCH, DES-004 | 2026-10-18 | [CR-006-configurable-cemu-connection.md](CR-006-configurable-cemu-connection.md) |
| CR-007 | Iniciar servidor Twinpad y mostrar QR en Cemu | SPEC-006, ARCH, DES-004 | 2026-10-18 | [CR-007-cemu-start-server-and-show-qr.md](CR-007-cemu-start-server-and-show-qr.md) |
| CR-008 | Captura automática y oculta del DRC para Twinpad | SPEC-006, ARCH, DES-004 | 2026-10-18 | [CR-008-automatic-hidden-drc-capture.md](CR-008-automatic-hidden-drc-capture.md) |
| CR-009 | Registrar Cemu antes de mostrar el QR de pairing | SPEC-006, ARCH, DES-004 | 2026-10-18 | [CR-009-register-cemu-before-qr.md](CR-009-register-cemu-before-qr.md) |
| CR-010 | Relay de plugins y selección de fuente desde Android | CTX, REQ-004/005/006, SPEC-002/003/004/005/006, ARCH, DES-004 | 2026-10-18 | [CR-010-plugin-relay-source-selection.md](CR-010-plugin-relay-source-selection.md) |
| CR-012 | Enrutamiento separado de audio TV en PC y GamePad/DRC en Android | SPEC-005, SPEC-006, ARCH, DES-004 | 2026-10-19 | [CR-012-cemu-tv-and-drc-audio-routing.md](CR-012-cemu-tv-and-drc-audio-routing.md) |

## Rejected / Withdrawn

| ID | Title | Targets | Reason (1 line) | File |
|---|---|---|---|---|
| — | — | — | — | — |

## Stats

| Metric | Value |
|---|---|
| Open | 0 |
| Median time open → applied | — |
| Last reviewed | 2026-10-20 |
