# Change Request (CR) — CR-105 — Twinpad

| | |
|---|---|
| ID | CR-105 |
| Title | El `framerate` con el que se abre el encoder es el presupuesto por frame del rate-control hardware: estimarlo con mediana y corregirlo cuando la cadencia real deriva |
| Type | `Defect fix` |
| Status | `Decided` (usuario, petición directa 2026-11-23) |
| Author | Buffy (agente) |
| Created | 2026-11-23 |
| Decided | 2026-11-23 |
| Decided by | Usuario (petición directa: «Corregir el rate-control de h264_vaapi en el plugin de Cemu para que respete el bitrate pedido») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-003` (§3 contrato de AU, frase de cadencia/rate-control) |
| Sections | SPEC-003 §3 (regla de recreación del encoder y metadata de rate-control) |
| Current version → new version | `SPEC-003 1.17.0 → 1.18.0` (aplicación pendiente por el owner; ver §7 nota de versión) |

## 2. Requested change

**Current (CR-036, aplicado):**

```text
Cemu no recrea el encoder por cambios de cadencia medida; la cadencia solo
alimenta la metadata del rate-control y el encoder se recrea únicamente cuando
cambian las dimensiones o el bitrate configurados.
```

**Proposed (aplicado en código):**

```text
La cadencia que alimenta el encoder se estima con la mediana de los intervalos
de la ventana de 12 frames (mínimo 5 muestras; con menos se conserva el último
valor). Ese valor se pasa como `framerate`/`time_base` al encoder y es el
presupuesto por frame del rate-control hardware
(`bits_per_frame = bit_rate / framerate`). Además de las dimensiones y el
bitrate, el encoder se recrea si la cadencia real se separa más del 25 % de la
que quedó grabada, de forma sostenida (90 frames) y fuera de un enfriamiento de
10 s.
```

## 3. Motivation

Síntoma: con `kb=15000` configurado, el stream real viaja a ~30 Mbps (IDR media 406 KB, P media 121 KB, 29,5 AU/s). El relay UDP lo descartaba o necesitaba un techo adaptativo (CR-104) para no tropezar.

**Medición de laboratorio (arnés VAAPI con el mismo FFmpeg 8.1.1 y driver del árbol de Cemu, 1280×720, `rc_mode=CBR`, `async_depth=1`, objetivos 15 000 kbps, 300 frames de contenido suave):**

| Variante | `time_base` | `framerate` | pts | Mbps obtenidos |
|---|---|---|---|---|
| base | 1/30 | 30 | `i` | **14,63** |
| fra15 | 1/30 | 15 | `i` | **29,63** |
| rt90ok | 1/90000 | 30 | `i`·3000 (tiempo real) | **14,63** |
| realtime90 | 1/90000 | 15 | `i`·3000 (tiempo real) | **29,63** |
| runtimefps | 1/30 | 15 al abrir, 30 por frame | `i` | **29,63** |

Conclusiones medidas:
1. El desbordamiento es exactamente el cociente `framerate_grabado / cadencia_real`: 15 grabado con 30 reales ⇒ 2× (29,63 Mbps), que clava el 2× del stream real.
2. Los `pts` **no** participan: ponerlos en tiempo real no corrige nada.
3. `framerate` **no** se relee en caliente: cambiarlo después de `avcodec_open2` no tiene efecto (variante `runtimefps`). Solo cuenta el valor con el que se abre.

**Causa en el plugin:** `cadenceFps` se calculaba como `(n-1)/span` sobre una ventana que puede estar recién nacida. Con dos muestras separadas 66 ms (un tirón del DRC) el valor es 15 fps, y desde CR-036 ese valor queda grabado en el encoder de por vida: solo se recrea al cambiar dimensiones o bitrate. Resultado: el encoder presupuesta `bit_rate/15` por frame mientras se emiten ~28-30 AU/s ⇒ ~30 Mbps.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | No | Sin cambio de requisito | None |
| SPEC-003 | Yes | Frase de cadencia/recreación (CR-036) | Update a 1.18.0 (pendiente owner) |
| DES-003/DES-004 | No | Sin cambio de ruta de captura ni readback | None |
| Android / servidor | No | Sin cambio de wire, contrato de AU intacto | None |
| Code | Yes | `TwinpadClient.h`, `TwinpadClientEncoder.cpp` (nuevo namespace `twinpad_cadence`), `main.cpp`, `Tests/Base64UrlTest.cpp` | Aplicado |
| Integrations / contracts | No | Sin cambio de wire | None |
| TEST | Yes | TEST-0201 (ver §7) | Row en `docs/testing/traceability.md` |

## 5. Options considered

1. **Mediana + corrección por deriva sostenida (aplicada).** Robusta a tirones, autocorrige el valor grabado, y el enfriamiento de 10 s más la persistencia de 90 frames acotan el coste (cada recreación crea contexto VAAPI y fuerza un IDR).
2. Arreglar solo la estimación inicial y seguir sin recrear nunca. Insuficiente: si la cadencia real cambia (juego a 20 fps, app a 60 fps), el bitrate vuelve a desviarse de forma permanente.
3. Recrear por cualquier cambio de cadencia (lo que CR-036 quitó). Descartada: oscila entre estados y produce IDR/reset continuos.
4. Ajustar `framerate` en caliente sin recrear. Descartada por medida: vaapi no lo relee (`runtimefps`).

## 6. Decision

- **Outcome:** Applied (petición directa del usuario, 2026-11-23). `twinpad_cadence::EstimateFps` (mediana, mínimo 5 muestras, fallback al último valor), `CadenceOff` (>25 %) y `DriftNeedsRecreate` (90 frames + 10 s de enfriamiento). El log `idr_diag` publica `cadence=` y `enc_fps=` y la línea `encoder recreate` añade `cadence_stale=` y `cadence=` para verificar en vivo.
- **Nota de versión (conflicto detectado, no resuelto aquí):** la cabecera de `SPEC-003` dice `1.17.0` mientras CR-104 declaró `1.18.0 → 1.19.0`; la aplicación de SPEC-003 para este CR debe partir del valor real que fije el owner.

## 7. Verification

| Item | Status | Evidence |
|---|---|---|
| Arnés VAAPI (fuera del repo) | Passed | Tabla §3: `fra15`/`realtime90`/`runtimefps` = 29,63 Mbps; `base`/`rt90ok` = 14,63 Mbps |
| Compilación del plugin | Passed | `ninja … TwinpadClientEncoder.cpp.o` y `…/Tests/Base64UrlTest.cpp.o` sin warnings |
| TEST-0201 `TwinpadClient_cadence_test` | Passed (compila; se ejecuta en builds con `CEMU_DEBUG_ASSERT`) | Mediana inmune a un tirón de 66 ms, fallback con ventana corta, umbral >25 % y enfriamiento de 10 s |
| Medición en vivo (2026-11-23, `bin/Cemu_release` reenlazado con el arreglo, Cemu reiniciado) | Passed | `~/.local/share/Cemu/log.txt`: `idr_diag … cadence=30 enc_fps=30` de forma sostenida (antes del arreglo el metadata quedaba en 15 con 30 fps reales) y `encoder recreate … cadence_stale=0 cadence=30 (854x480 kb=10000)`. Con la app pidiendo 10000 kbps: 14,37 Mbps en la interfaz y ~10,91 Mbps de payload (factor **1,09×**), 26,4 AU/s, 0 descartes por crédito en la ventana. Antes del arreglo, mismo escenario a 15000 kbps: 30,4 Mbps (factor **2,03×**) |

Notas de la verificación en vivo:

- El log del plugin **no** sale por stdout: `cemuLog` escribe en `<userdata>/log.txt` (`~/.local/share/Cemu/log.txt`). Lanzar Cemu con la salida redirigida a un fichero no muestra ninguna línea `[Twinpad*]`.
- El factor se calcula sobre bytes de payload: overhead medido de FEC (`FEC_GROUP_SIZE=4`, +25 %) y cabeceras (`DATAGRAM_HEADER_BYTES=15` + tag + IP/UDP/L2) ≈ 1,317× sobre `MAX_PAYLOAD_BYTES=1360`; `tx_bytes` de la interfaz es la fuente (los sockets del relay son `sendto` sin contadores por peer y `wchar` no cuenta `sendto`).
- El residual de 1,09× está dentro del lazo de CBR de VAAPI; el arnés §3 da 0,98× con contenido sintético, así que no queda deriva de cadencia.
