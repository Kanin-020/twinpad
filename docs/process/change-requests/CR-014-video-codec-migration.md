# Change Request (CR) — CR-014 — Twinpad

| | |
|---|---|
| ID | CR-014 |
| Title | Migrar la Segunda pantalla de JPEG/tiles a vídeo codificado |
| Type | Behavior change |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-20 |
| Decided | 2026-10-20 |
| Decided by | Product owner (migración y contrato técnico detallado) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003, ARCH, DES-003, DES-004, testing/traceability.md |
| Sections | SPEC-003 §§2, 4, 7–10, 12–16; architecture media path; video designs and verification |
| Current version → new version | SPEC-003 1.3.0 → 1.4.0 |

## 2. Requested change

**Current:** SPEC-003 transmite imágenes: keyframes `video_frame` JPEG y parches `video_patch` JPEG de tiles 64×64, codificados en base64url sobre NDJSON. El servidor compara RGB y selecciona JPEG completo o tiles; Android decodifica a Bitmap y compone parches. Cemu publica RGB8 por IPC local.

**Approved:** Cemu captura la textura DRC, la convierte a I420 y codifica AVC por software con OpenH264 (Constrained Baseline, nivel 4.2, sin B-frames; SPS/PPS en cada IDR). IPC local entrega access units Annex B vía `game_frame` Base64URL temporal hasta migrar el SDK a IPC binario. El Servidor PC selecciona y retransmite access units sin decodificar ni recodificar; NDJSON/control conserva Pairing, selección, inputs y `stream_start/stop`. Android abre TCP al puerto fijo 8425 y decodifica con `MediaCodec` en `SurfaceView`. No hay fallback a JPEG ni coexistencia de versiones: actualización coordinada.

El canal es bidireccionalmente autenticado: el cliente envía `session_id` (16 B), `nonce_c` aleatorio (32 B) y HMAC-SHA256(session_key, `"twinpad.v1 media client" || session_id || nonce_c`); el servidor responde `nonce_s` aleatorio (32 B) y HMAC-SHA256(session_key, `"twinpad.v1 media server" || session_id || nonce_c || nonce_s`). Ambos derivan la clave direccional de servidor-a-cliente con HKDF-SHA256, salt=`nonce_c || nonce_s`, info=`"twinpad.v1 media server-to-client aes-256-gcm"`. El server conserva `session_key` solo en memoria. Cada registro contiene `u32 BE length` (bytes siguientes, incluido tag), `u64 BE counter` iniciando en 0 y ciphertext AES-256-GCM + tag de 16 B. Nonce GCM=`0x00000000 || counter_u64_be`; AAD=`length_u32_be || counter_u64_be`. Rechazar registros >4 MiB, counter repetido/saltado, tag inválido o truncamiento; un canal nuevo repite handshake con nonces aleatorios nuevos y nuevas subclaves. Payload cifrado: `fn u64 BE || ts_capture_us u64 BE || flags u8 (bit0=IDR; otros bits 0) || access_unit Annex B`; timestamp monotónico desde el encoder, sin B-frames. La conexión principal de Pairing confirma `session_id`; su cierre revoca inmediatamente el socket media asociado. El publicador Cemu no recibe la clave: el media socket de red pertenece al Android autenticado; el server relaya solo la fuente seleccionada y conserva un solo access unit pendiente (latest-frame-wins).

`stream_start` anuncia `fmt:"h264"`, dimensiones, fps y `media_port:8425`. Dimensiones válidas según SPEC-003; un cambio exige `stream_stop`, nueva configuración encoder y `stream_start`, seguido por IDR. La primera unidad y cada recuperación llevan IDR con SPS/PPS. La app descarta access units no-IDR hasta IDR después de inicio/cambio/reconexión; al parar o cambiar fuente cierra/reset decoder y superficie. NFR-001/005 y objetivo 1080p60 requieren medición física; el perfil no garantiza ese rendimiento.

Screenshots puntuales no pertenecen a esta migración: la ruta Twinpad ya toma el DRC del render target; la API independiente de screenshot de Cemu permanece intacta.

## 3. Motivation

La integración E2E anterior (TEST-057) recibía ~3.77 fps a 1920×1080. La migración elimina la expansión RGB/Base64 y el JPEG/tile diff del relay; no se afirma que el codec garantice 60 fps.

Aclaración: las screenshots puntuales de Cemu no alimentan el stream Twinpad actual; la ruta obtiene el framebuffer DRC desde el render target. La propuesta no requiere borrar ni modificar la funcionalidad independiente de screenshots de Cemu.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/REQ-005/REQ-006 | Revisado | El contrato conserva la Segunda pantalla y la fuente Plugin; se reemplaza solo la representación/transporte | SPEC-003 actualizado |
| SPEC-003 | Sí | Sustituir JPEG/tiles por H.264 y canal binario autenticado | Actualizado a 1.4.0 |
| ARCH | Sí | Encoder Cemu, relay opaco PC, decoder Android | Actualizado por CR-014 |
| DES-003/DES-004 | Sí | Diseñar canal seguro, encoder, relay y presentación | Actualizado por CR-014 |
| TEST | Sí | Verificar crypto/relay/encoder/decoder; medir NFR en físico | TEST-071..074 inscritos; banco pendiente |
| Code | Sí | Cemu encoder/IPC, Server relay y Android decoder/presentación | Implementación en ejecución |
| Data / migrations | No esperado | Sin persistencia de usuario prevista | Confirmar al diseñar |
| Integrations / contracts | Sí | Wire H.264 incompatible con JPEG/tiles | Rollout coordinado sin compatibilidad hacia atrás |

## 5. Options considered

1. **H.264/AVC con OpenH264 software en Cemu; relay opaco en server; Android MediaCodec; TCP binario separado** — aprobada. Parámetros normativos fijados en §2 y SPEC-003; rendimiento físico sigue como gate, no promesa.
2. **H.264/AVC, encode en el Servidor PC; Android MediaCodec decoder** — no seleccionada; conserva RGB/Base64 en IPC y añade encoder en el Server.
3. **Mantener JPEG/tiles** — no seleccionada para esta propuesta; evita el cambio del wire, pero conserva el pipeline actual.

La aprobación de CR-014 autoriza actualizar SPEC-003 y realizar el cambio coordinado de implementación descrito aquí; hasta completar ese rollout no hay compatibilidad entre el wire JPEG/tiles antiguo y el H.264 nuevo.

## 6. Decision

- **Outcome:** Approved — H.264/AVC desde Cemu; suite AES-256-GCM + HKDF-SHA256; HMAC-SHA256 para handshake mutuo; TCP media 8425; framing con length u32 BE y counter u64 BE; AVC Constrained Baseline nivel 4.2.
- **Rationale:** El plugin reduce carga del IPC publicando access units comprimidas; el server relaya sin conversión. El canal Android se autentica con la clave de la sesión actual y cifra cada registro con AEAD; los contadores y subclaves por conexión impiden replay/nonce reuse.
- **Rollout:** Coordinado e incompatible: Cemu, Servidor PC y Android deben actualizarse juntos. Captura/screenshot separada queda fuera del stream Twinpad y no se modifica.

## 7. Execution checklist

- [x] Owner selecciona dirección: H.264/AVC con OpenH264 software en Cemu, relay opaco en server, MediaCodec decoder Android, canal TCP binario separado.
- [x] Owner selecciona autenticación HMAC y cifrado AEAD derivado de la sesión para el canal extra, conforme a NFR-004.
- [x] Owner concreta suite AES-256-GCM/HKDF-SHA256/HMAC-SHA256, puerto 8425, handshake mutuo, framing/counter y anti-replay.
- [x] Owner aprueba perfil AVC, reloj monotónico, IDR/SPS/PPS, reset ante cambio de dimensión y rollout incompatible.
- [x] SPEC-003, ARCH y diseños actualizados mediante este CR.
- [x] TEST-071..074 reservados e inscritos en `testing/traceability.md`.
- [ ] Implementación Cemu/Server/Android verificada, incluyendo prueba end-to-end y medición de FPS/latencia.
- [x] Indexes y trazabilidad actualizados; implementación y verificación física siguen pendientes.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-20 | Twinpad team | CR abierto para decidir migración de vídeo codificado |
| 0.2.0 | 2026-10-20 | Product owner + Twinpad team | Aprobados AVC/OpenH264, suite criptográfica, handshake, puerto, framing, anti-replay y reset; implementación autorizada |
