# SPEC-005 — Transmisión y reproducción de audio

| | |
|---|---|
| ID | SPEC-005 |
| Title | Transmisión y reproducción de audio (REQ-005) |
| Version | 1.2.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-005 (AC-1, AC-2 parcial: reanudación compartida con SPEC-003) |
| Realized by | DES-004 (CR-010; Draft) |
| Verified by | TEST-020, TEST-051 |
| Last updated | 2026-10-18 |

## 1. Purpose

Fija el comportamiento observable del Stream de audio que el Plugin seleccionado publica y el Servidor PC retransmite al Android device durante la sesión: presentación, sincronía con SPEC-003 y recuperación ante cortes. El Servidor no captura ni sintetiza audio.

## 2. Definitions

- **Stream de audio**: secuencia ordenada de fragmentos de audio (PCM codificado) transmitida PC → Android.
- **Audio activo**: el fragmento más reciente que la app tiene listo para reproducir.
- **Jitter buffer**: tolerancia temporal de llegada antes de considerar el stream interrumpido.
- Ver CTX §8 (Stream, Segunda pantalla) y SPEC-003 §2 (Frame number, Degradación).

## 3. Preconditions

- Sesión autenticada (SPEC-001) con la misma conexión persistente de SPEC-003.
- La sesión autenticada ha confirmado un Plugin con fuente PCM activa (SPEC-006 §7).
- Si no hay una fuente PCM activa, la app permanece en silencio (SPEC-005 §5).

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `type` | string | exactamente `"audio_start"` \| `"audio_chunk"` \| `"audio_stop"` | yes |
| `audio_start`: `rate` | integer (uint32) | muestras por segundo, 8 000–192 000 | yes |
| `audio_start`: `channels` | integer (uint8) | 1 (mono) o 2 (estéreo) | yes |
| `audio_start`: `sample_fmt` | string | `s16le` (v1) | yes |
| `audio_chunk`: `seq` | integer (uint64) | secuencial creciente por stream | yes |
| `audio_chunk`: `ts` | integer (uint64) | milisegundos del reloj del server en el envío | yes |
| `audio_chunk`: `data` | string | base64url del fragmento; tamaño ≤ 16 KiB decodificado | yes |
| `audio_stop`: `reason` | string | `plugin_stopped` \| `server_shutdown` | yes |

Canal Plugin→Servidor: IPC de audio de SPEC-006 §4. Canal Servidor→Android: la conexión NDJSON autenticada de la sesión (SPEC-001 §4.2), solo para el Plugin confirmado por `game_selected`. El Servidor adapta y retransmite PCM; nunca captura ni sintetiza audio. El escritor de sesión transmite mensajes de video antes de mensajes de audio pendientes; la cola de audio es acotada y descarta chunks antiguos ante congestión (R3/R4).

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Audio del juego audible | reproducción continua en el Android durante la sesión | `audio_start` + chunks llegando |
| Sin audio | silencio, sin error al usuario | sin `audio_start` vigente |
| Fragmento corrupto descartado | el audio continúa con el siguiente fragmento | `data` inconsistente con parámetros |

## 6. States

- `A0 Inactive`: sin audio; silencio.
- `A1 Playing`: chunks llegando y reproduciéndose.
- `A2 Interrupted`: no llega audio dentro del jitter buffer (≥ 500 ms de hambre).

| From | Event | To | Side effects |
|---|---|---|---|
| `A0` | `audio_start` | `A1` | se configura la salida con `rate`/`channels`/`sample_fmt` |
| `A1` | `audio_chunk` con `seq` = último+1 | `A1` | fragmento encolado para reproducción |
| `A1` | `audio_chunk` con `seq` ≤ último | `A1` | descartado (idempotencia) |
| `A1` | sin chunks ≥ 500 ms | `A2` | indicador de reconexión en la vista |
| `A2` | `audio_chunk` tras reconectar | `A1` | reproducción reanudada sin re-pairing (REQ-005 AC-2) |
| `A1`/`A2` | `audio_stop` o fin de sesión | `A0` | silencio |

Invariant: en `A1` el desfase entre la presentación del audio y el frame de SPEC-003 no supera la tolerancia de sincronía (±150 ms, medible; se afina en DES si el banco físico lo requiere).

## 7. Main flow

1. El Plugin inicia una fuente PCM en el SDK; el SDK usa su conexión IPC de audio separada y envía `game_audio_start` asociado a `game_id`.
2. La fuente callback produce bloques PCM `s16le` en un hilo SDK dedicado. El SDK descarta los bloques más antiguos de su cola acotada ante congestión y transmite `game_audio_chunk` de hasta 16 KiB; el Server, solo si el Plugin está seleccionado, adapta estos mensajes a `audio_start`/`audio_chunk` con secuencia incremental y `ts` de envío.
3. El escritor de sesión prioriza video en los límites de mensaje y vacía audio pendiente cuando no hay video en espera; la app decodifica y encola los chunks para reproducción continua.
4. Al terminar la fuente, el SDK envía `game_audio_stop`; el Server emite `audio_stop` y la app queda en silencio. Sin fuente, no se envían mensajes de audio.

## 8. Alternative flows

### 8.1 Cambio de Plugin seleccionado

- **Trigger:** Android selecciona otra fuente y recibe confirmación `game_selected`.
- **Flow:** el Servidor envía `audio_stop` para la fuente previa antes de retransmitir PCM de la nueva; su secuencia inicia según el contrato de `audio_start`.
- **Result:** no se mezclan fragmentos de audio de Plugins distintos.

### 8.2 Solo audio sin segunda pantalla (y viceversa)

- **Trigger:** el juego emite audio sin contenido de video, o video sin audio.
- **Flow:** cada stream es independiente (estados de SPEC-003 y de este SPEC no se afectan entre sí).
- **Result:** el audio se reproduce sin segunda pantalla activa, o la pantalla se muestra en silencio.

### 8.3 Corte transitorio de red

- **Trigger:** caída breve < 10 s (igual criterio que SPEC-003 §8.2).
- **Flow:** al restablecerse, el Server continúa con `seq` creciente sobre la sesión reanudada; el hueco de audio no se retransmite (R3 del SPEC-003 aplicado a audio).
- **Result:** la reproducción se reanuda en el fragmento más reciente; sin re-pairing.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| `audio_chunk` con `data` de longitud inválida | fragmento descartado; la reproducción continúa | sin mensaje al usuario |
| `audio_chunk` con `seq` ≤ último | descartado silenciosamente | — |
| Mensaje de audio fuera de rango o con `type` desconocido | violación de protocolo: fin de sesión con el mecanismo de SPEC-002 | `E4` |
| `audio_start` sin sesión autenticada | conexión rechazada | `E3` |
| Hambre de audio ≥ 500 ms | estado `A2` con indicador; sin error final hasta reconexión imposible | indicador |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Chunks con parámetros distintos al `audio_start` vigente | descartados (inconsistencia) |
| Reloj del server retrocede (`ts` no monótono) | la ordenación es por `seq`; `ts` solo diagnóstico |
| Dispositivo de audio por defecto cambiado a mitad de sesión en la PC | el server re-anuncia `audio_start` con los parámetros nuevos; la app re-configura la salida sin cortar la sesión |
| Sin Plugin seleccionado o Plugin no seleccionado con PCM activo | el Servidor no envía ese PCM y Android permanece en silencio |
| El usuario silencia el audio en la app | se deja de reproducir localmente; los chunks siguen llegando y descartándose |

## 11. Business rules

- **R1:** el audio solo existe sobre sesión autenticada (NFR-004).
- **R2:** el orden lo determina exclusivamente `seq`.
- **R3:** los fragmentos perdidos no se retransmiten; la reproducción avanza al más reciente (coherente con R3 de SPEC-003).
- **R4:** ante congestión, el Server prioriza video sobre audio en cada límite de mensaje y el SDK/Server descartan PCM antiguo en colas acotadas; el audio no bloquea al productor de frames ni retiene chunks obsoletos.
- **R5:** la sincronía A/V se mantiene dentro de ±150 ms; si el desfase lo excede, la app re-sincroniza descartando audio atrasado.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Mensajes de audio PC→Android (`audio_start`/`audio_chunk`/`audio_stop`) | este documento §4; NDJSON según SPEC-001 §4.2 | v1 |
| Mensajes IPC de audio Plugin→Servidor (`game_audio_start`/`game_audio_chunk`/`game_audio_stop`) | SPEC-006 §4 | conexión TCP localhost separada; solo se retransmite para la fuente seleccionada |
| Sesión autenticada | SPEC-001 §7/§8 | referenciado, no duplicado |
| Códec de compresión | DES (por asignar) | v1: `s16le` PCM sin compresión; códec por CR |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Flujo principal (REQ-005 AC-1)
  Given sesión autenticada y el juego emitiendo audio
  When el server envía audio_start(44100, estéreo, s16le) y chunks consecutivos
  Then el teléfono reproduce el audio durante toda la sesión

Scenario: Fragmento corrupto no rompe la reproducción
  Given audio en A1 con seq=100 reproducido
  When llega audio_chunk(seq=101) con data inconsistente
  Then el fragmento se descarta y el 102 se reproduce normalmente

Scenario: Solo audio sin segunda pantalla (§8.1)
  Given sesión activa y el juego emitiendo solo audio
  When el server envía audio_start y chunks
  Then el teléfono reproduce el audio sin segunda pantalla activa
```

## 14. Invariants

- `seq` estrictamente creciente en los chunks aceptados.
- Ningún audio se reproduce antes de un `audio_start` vigente.
- Tras cada chunk (válido o no), la app está reproduciendo el audio más reciente posible o en silencio; jamás ruido decodificado de un buffer inconsistente.

## 15. What "complies" means

Una implementación cumple este SPEC si y solo si: honra §3, produce §5 en cada condición, reproduce cada error de §9 con su trigger, cumple los edge cases de §10, mantiene §14 y pasa §16. Lo no definido no es requerido.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Flujo principal (§7) y AC-1 de REQ-005 | TEST-020, TEST-037 |
| PCM real por SDK, sin fuente en silencio y prioridad video (§3/§5/§7/R4) | TEST-036, TEST-037 |
| Reanudación tras corte (§8.3, AC-2) | compartido con TEST-017 de SPEC-003 |
| Relay de audio de fuente seleccionada y cambio de Plugin | TEST-051 |
| Sincronía A/V (±150 ms) y NFR-006/007 | pruebas de performance en banco físico |

## 17. Open questions

- ¿Códec de compresión para el audio (o PCM crudo es suficiente para v1 en 802.11ac)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario, 2026-10-08): v1 sin compresión — PCM `s16le` crudo, igual criterio que SPEC-003 (OQ-1); si la medición de ancho de banda fallara, el códec entra por CR.**
- ¿Tolerancia de jitter buffer (500 ms inicial) ajustable por el usuario? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario, 2026-10-08): fija en 500 ms en v1; no se expone ajuste al usuario (superficie de configuración mínima); ajustable solo vía CR si el banco físico lo exige.**

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: stream de audio sobre la sesión |
| 1.0.0 | 2026-10-08 | Owner (usuario) | Approved: OQ §17 resueltas (PCM s16le sin compresión; jitter 500 ms fijo) |
| 1.1.0 | 2026-10-17 | Owner + Twinpad team (CR-003) | Origen PCM por callback SDK, canal IPC separado, colas acotadas drop-old y prioridad de video por mensaje |
| 1.2.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | El Servidor solo retransmite PCM del Plugin confirmado; cambio/fallo de fuente termina audio previo |
