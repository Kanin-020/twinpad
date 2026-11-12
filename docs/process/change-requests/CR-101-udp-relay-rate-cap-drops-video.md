# CR-101 — El tope de tasa del relay UDP descarta AU reales y deja el vídeo a ~20 fps

## 0. Metadatos

| Campo | Valor |
|---|---|
| ID | CR-101 |
| Título | El token bucket de CR-083 (anclado a 10 000 kbps) descarta AU no-IDR mientras Cemu produce ~13,5 Mbps; el teléfono recibe ~24 fps y ve cortes |
| Fecha | 2026-11-12 |
| Reportado por | owner («Render roto… 20 fps recibidos 30 fps renderizados… inputs se cortan en reconexión») |
| Estado | `Applied` (2026-11-12, pendiente de verificación en vivo) |

## 1. Evidencia (2026-11-12, log del servidor `/tmp/twinpad-server.log`)

- Cemu configurado a 10 000 y 15 000 kbps (`Video config actualizada por la app: … 10000 kbps`; `CR-058: congestión media — encoder a 15000 kbps`).
- `udp-relay tasa: descartadas N AU por crédito (kbps=10000)` con N acumulado en millones: el crédito se agota de forma continua.
- Canal activo (`192.168.100.143:60659`) en una ventana de 52,5 s: 1 277 AU enviadas, ~24,3 fps, ~57,8 datagramas por AU (~67,8 KB), ~13 500 kbps reales.
- Hay varios puertos del teléfono enlazados a la vez (`40513`, `47002`, `49089`, `58614`, `60659`); cada re-JOIN crea un canal nuevo y el anterior muere (`perdió el dueño`).
- Las AU descartadas son no-IDR: la cadena de referencias H.264 se rompe y el decoder del teléfono salta frames (recibidos ≠ renderizados).

## 2. Cambio solicitado (propuesta, sin implementar)

Decidir con el owner entre:

- **Opción A (recomendada):** que el tope de crédito use el bitrate realmente configurado en Cemu (`configured_kbps`, no el mínimo de 10 000 ni el `effective_kbps` degradado) y que no descarte AU no-IDR mientras haya bitrate configurado; el control de congestión queda en la escalera (CR-058).
- **Opción B:** eliminar el token bucket de CR-083 y confiar en el control de congestión y en el `latest-frame-wins` del teléfono.

En ambas, la AU descartada no debe dejar el decoder sin referencias: si se descarta una AU no-IDR, la siguiente debe reclamar IDR.

## 3. Motivación

CR-083 se aprobó para cortar un flood de 6 600 datagramas/s. Hoy el mismo mecanismo recorta el vídeo a ~24 fps frente a los ~30 que Cemu produce, y deja el stream entrecortado. El límite debe respetar el bitrate configurado, no un piso fijo menor.

## 4. Impacto

- `server/src/media_udp.rs` (`relay_udp_session`, ~420–445), `server/src/media.rs` (`effective_kbps`).
- SPEC-003 §4.3 y TEST-0168 (token bucket) deben revisarse con un CR de SPEC si cambia el contrato.
- No cambia el wire.

## 5. Alternativas consideradas

- Subir el piso a 15 000 kbps: no resuelve el descarte si el encoder sube.
- Quitar el bucket: riesgo de volver al flood de CR-083.

## 6. Decisión

Opción A aprobada por el owner (2026-11-12). Aplicada: el piso del crédito baja de 10 000 a 1 000 kbps y el crédito sigue al `effective_kbps` de la escalera (que solo baja por congestión real), en `server/src/media_udp.rs` (`relay_udp_session`). La IDR sigue sin pasar por el tope y la AU mayor que el cap sigue descartándose (TEST-0168 se mantiene).

## 7. Checklist de ejecución

- [x] Decidir Opción A o B.
- [x] Ajustar código del servidor (`media_udp.rs`); TEST-0168 sin cambios, pasa (`cargo test -p twinpad-server --lib token_bucket` EXIT=0).
- [x] Build release del servidor y reinicio (`cargo build --release -p twinpad-server` EXIT=0).
- [ ] Verificar en vivo: fps recibidos ≈ renderizados y sin cortes en reconexión. Pendiente: requiere que el teléfono vuelva a estar en `adb` o que el owner confirme en la app.

## Changelog

- 2026-11-12: creado (Open) con evidencia del log.
- 2026-11-12: opción A aprobada y aplicada; TEST-0168 pasa; servidor reconstruido y reiniciado. Verificación en vivo pendiente.
