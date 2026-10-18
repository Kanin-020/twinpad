# Change Request — CR-004 — Adaptador experimental Cemu para prueba semi-real de Twinpad

| | |
|---|---|
| ID | CR-004 |
| Title | Adaptador experimental Cemu para prueba semi-real de Twinpad |
| Type | `Scope change` |
| Status | `Approved` |
| Author | Twinpad team |
| Created | 2026-10-17 |
| Decided | 2026-10-17 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | CTX §4; ARCH §2/§3/§6; SPEC-006 §4/§7 |
| Sections | Integración directa experimental de Cemu al IPC local Twinpad |
| Current version → new version | CTX 0.1.0 → 0.2.0; ARCH 0.1.0 → 0.2.0; SPEC-006 1.1.0 → 1.2.0 |

## 2. Requested change

**Current:** CTX §4 excluye soporte directo para CEMU y ARCH/SPEC-006 solo describen juegos que usan SDK Twinpad Rust.

**Approved:** Se permite un adaptador experimental nativo dentro del checkout Cemu `https://github.com/Kanin-020/Cemu` para una prueba semi-real, sin declarar soporte general de emuladores. El adaptador anuncia un juego por IPC local Twinpad, publica la textura GamePad mediante el canal de frames y publica PCM DRC por el canal `game_audio_*` separado. El menú superior `Twinpad` se ubica inmediatamente entre `NFC` y `Debug`. La captura GamePad reutiliza la ruta de screenshot del backend y queda limitada a 5 fps para acotar el impacto del readback síncrono; el audio fuente DRC opera a 48 kHz y no bloquea el hilo productor.

## 3. Motivation

El Product owner solicita usar Cemu como primera prueba semi-real del SDK Twinpad y añadir una entrada de menú propia. Cemu ya proporciona un punto PCM GamePad a 48 kHz en `AIInitDRCDMA` y rutas de readback GamePad en sus renderers; estas rutas se diseñan para screenshots, por lo que la prueba limita deliberadamente la frecuencia de captura.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| CTX | Sí | La exclusión absoluta de Cemu pasa a permitir un adaptador experimental puntual | Actualizar §4; mantener fuera soporte general |
| ARCH | Sí | Se añade Cemu como cliente/adaptador experimental vía IPC local | Actualizar componentes, límites e integración |
| SPEC-006 | Sí | Añade el adaptador Cemu como excepción de prueba semi-real | Actualizar precondiciones y flujo |
| SPEC-005 | No | Audio sigue el contrato PCM de CR-003 | Ninguna |
| TEST | Sí | TEST-038 verifica conformidad protocolo/cliente Cemu | Registrar en trazabilidad Twinpad y pruebas de Cemu |
| Code | Sí | Menú, cliente TCP local, readback GamePad limitado a 5 fps y copia PCM DRC | Cambios en ambos repositorios locales |
| Integrations / contracts | Sí | Cemu utiliza `127.0.0.1:8421` y `:8422` sin SDK Rust embebido | Sin cambio de wire protocol |

## 5. Options considered

1. Adaptador experimental directo en Cemu — permite verificar el camino real de GamePad/audio sin esperar una integración del juego huésped.
2. Do nothing — Cemu no puede demostrar el recorrido IPC en una prueba semi-real.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El Product owner autorizó explícitamente la integración C++ nativa directa. El alcance se limita a un adaptador experimental para Cemu; no implica soporte genérico de emuladores ni cambia el SDK Twinpad oficial. La captura con readback síncrono queda explícitamente acotada a 5 fps y se puede desactivar desde el menú.
- **Rollout:** Prototipo de desarrollo local; no constituye release de soporte Cemu.

## 7. Execution checklist

- [ ] CTX, ARCH y SPEC-006 reflejan el alcance puntual aprobado
- [ ] Menú `Twinpad` entre `NFC` y `Debug` y conexión IPC de Cemu implementados
- [ ] Captura GamePad opt-in limitada a 5 fps; PCM DRC en hilo desacoplado
- [ ] TEST-038 y trazabilidad añadidos
- [ ] Build/test de Twinpad y Cemu revisados

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-17 | Product owner + Twinpad team | Aprueba una excepción experimental Cemu para prueba semi-real |
