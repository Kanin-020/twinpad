# Change Request — CR-002 — Actualizaciones parciales del Stream de video

| | |
|---|---|
| ID | CR-002 |
| Title | Actualizaciones parciales del Stream de video por tiles |
| Type | New behavior |
| Status | Approved |
| Author | Usuario + agente |
| Created | 2026-10-16 |
| Decided | 2026-10-16 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003 |
| Sections | §2, §4, §5, §6, §7, §9, §10, §11, §12, §14, §16 |
| Current version → new version | 1.1.0 → 1.2.0 (aplicado) |

## 2. Requested change

**Current:** SPEC-003 §2 define el Framebuffer como imagen completa y §4/§7 solo permite `video_frame` con los bytes de la imagen completa.

**Proposed:** añadir el mensaje NDJSON `video_patch` para reemplazar tiles de la imagen vigente, sin retirar `video_frame` ni cambiar el encuadre/codec JPEG existente.

Contrato propuesto para `video_patch`:

```json
{"type":"video_patch","fn":101,"base_fn":100,"ts":1234,"w":1280,"h":720,"tile_size":64,"patches":[{"x":64,"y":128,"w":64,"h":64,"fmt":"jpeg","data":"<base64url JPEG del tile>"}]}
```

- `fn` (wire JSON `fn`; el identificador Rust `fn_` se serializa a `fn`) es monótono dentro del stream; `base_fn` debe ser el último mensaje de imagen emitido/aplicado. `fn` aumenta solo por keyframes o patches realmente enviados; frames fuente sin cambios no consumen número.
- `x`/`y` son coordenadas en píxeles, alineadas a múltiplos de 64; `w`/`h` son 64 excepto en bordes. Cada `data` es un JPEG independiente de exactamente `w×h`.
- El emisor compara cada tile RGB contra la imagen anterior transmitida y agrupa los tiles modificados en un solo mensaje. No envía mensaje si no cambió ningún tile.
- Al recibir un patch cuyo `base_fn` coincide, Android decodifica y compone todos sus tiles sobre el frame vigente como una operación; si no coincide o un tile está corrupto, descarta el mensaje completo y espera un keyframe.
- `video_frame` sigue siendo el keyframe completo. El servidor envía uno al iniciar/cambiar resolución y como máximo cada 1 s. Si los JPEG de tiles de un update ocupan lo mismo o más que un JPEG completo, envía un keyframe en su lugar.
- `stream_start` anuncia `fmt:"jpeg_tiles"`; esta versión requiere actualizar el servidor PC y Android juntos. No se conserva compatibilidad con versiones antiguas y no se añade negociación de capacidades al handshake. Los keyframes `video_frame` llevan `fmt:"jpeg"`; los parches declaran `fmt:"jpeg"` en cada tile.
- Android aplica cada `video_patch` en orden; no se permite descartar parches pendientes de la cola, porque cada `base_fn` depende de su predecesor. Si el procesamiento no alcanza la tasa, los mensajes cuyo `base_fn` ya no coincide se descartan y se espera el siguiente keyframe periódico (≤1 s); no se compone un patch sobre una base incorrecta.
- Audio/input siguen independientes y prioritarios; un patch nunca ocupa ni bloquea el canal de inputs. El fallback periódico limita a ≤1 s el tiempo máximo hasta recuperar sincronía tras perder un patch.

## 3. Motivation

El owner pide enviar/renderizar solo zonas que cambian. La sesión física del 2026-10-16 presentó ~27–32 FPS con JPEG completo; la captura óptica de latencia NFR-001 aún está pendiente. El escritorio contiene regiones estáticas, por lo que JPEG por tile podría reducir bytes por update y CPU de codificación/decodificación. La comparación de tiles sigue inspeccionando cada píxel, y la ganancia depende del contenido; se medirá en el banco antes de afirmar mejora.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 | Sí | La Segunda pantalla continúa mostrando frame completo al usuario | Confirmar que no cambia el AC |
| SPEC-003 | Sí | Añade tipo, estado de composición, keyframes y recuperación de base | Actualizado a v1.2.0 en esta iteración |
| ARCH | No | Se conserva transporte de sesión; Captura/servidor producen imágenes | Ninguna si se mantiene esa frontera |
| DES | Sí | Realización del video por tiles en DES-003 | DES-003 creado (Draft, pendiente de revisión) |
| TEST | Sí | Contratos tiles, base mismatch, corrupción, keyframe y medición | TEST-027/028 e inscribir en matriz |
| Code | Sí | Comparador RGB por tiles en server; decoder/compositor Android | Cambios Rust y Kotlin |
| Data / migrations | No | Sin persistencia ni migraciones | Ninguna |
| Integrations / contracts | Sí | Extiende el NDJSON de SPEC-003; `stream_start` selecciona `jpeg_tiles` | App y server se actualizan juntos, sin negociación con versiones antiguas |

## 5. Options considered

1. **Tiles fijos de 64×64 (propuesta):** controla el desperdicio cuando hay cambios separados; permite omitir regiones estáticas.
2. Rectángulo envolvente: protocolo más simple, pero un movimiento pequeño entre extremos puede forzar muchos píxeles sin cambios.
3. Do nothing: mantener keyframes JPEG completos; seguro y compatible, pero no cumple la optimización solicitada.

## 6. Decision

- **Outcome:** Approved
- **Rationale:** El owner aprobó la modalidad de tiles fijos de 64 px, keyframes completos como máximo cada segundo y fallback al keyframe completo si los patches no reducen bytes. El owner confirmó que no requiere compatibilidad con clientes antiguos: el servidor y Android se actualizarán juntos y `jpeg_tiles` se usará sin negociación de capacidades.
- **Rollout:** Actualización conjunta server + Android; las versiones anteriores no son compatibles con el nuevo contrato.

## 7. Execution checklist

- [x] Owner aprueba CR-002.
- [x] Actualizar SPEC-003 a v1.2.0, versión y changelog (sin cambio de estado del índice).
- [x] Asignar TEST-027/028 e inscribir trazabilidad.
- [x] Implementar servidor y cliente con composición atómica y recuperación por keyframe (TEST-027/028; gates locales verdes).
- [x] Crear DES-003 para la realización de la implementación.
- [ ] Medir bytes, FPS, CPU y latencia en imágenes quietas, movimiento pequeño y pantalla cambiante en el banco físico.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-16 | Usuario + agente | Propuesta abierta de video por tiles |
| 0.2.0 | 2026-10-16 | Usuario | Aprobado: no se mantiene compatibilidad con clientes antiguos; actualización conjunta Android/servidor |
| 0.3.0 | 2026-10-16 | Usuario + agente | Aclarados el nombre wire `fn`, asignación de secuencia solo a imagen emitida y procesamiento ordenado de parches; SPEC-003 v1.2.0 aplicado |
