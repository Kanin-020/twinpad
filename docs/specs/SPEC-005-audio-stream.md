# SPEC-005 — Transmisión y reproducción de audio

| | |
|---|---|
| ID | SPEC-005 |
| Title | Transmisión y reproducción de audio (REQ-005) |
| Version | 0.1.0 |
| Status | `Draft` |
| Owner | Eng lead |
| Implements | REQ-005 (AC-1, AC-2 parcial: reanudación compartida con SPEC-003) |
| Realized by | DES-XXX (`-` hasta asignar) |
| Verified by | TEST-020 |
| Last updated | 2026-10-07 |

## 1. Purpose

Fija el comportamiento observable del Stream de audio del juego desde el PC Host hacia el Android device durante la sesión: presentación, tolerancia de sincronía con el video de SPEC-003 y recuperación ante cortes transitorios. El códec concreto es decisión de DES (OQ abierta de REQ-005 §11).

## 2. Definitions

- **Stream de audio**: secuencia ordenada de fragmentos de audio (PCM codificado) transmitida PC → Android.
- **Audio activo**: el fragmento más reciente que la app tiene listo para reproducir.
- **Jitter buffer**: tolerancia temporal de llegada antes de considerar el stream interrumpido.
- Ver CTX §8 (Stream, Segunda pantalla) y SPEC-003 §2 (Frame number, Degradación).

## 3. Preconditions

- Sesión autenticada (SPEC-001) con la misma conexión persistente de SPEC-003.
- El Server PC tiene audio disponible del juego integrado (origen: Plugin, fuera de alcance aquí).

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

Canal: la misma conexión NDJSON de la sesión (SPEC-001 §4.2); una línea por mensaje.

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

1. Con la sesión establecida y contenido sonoro disponible, el Server envía `audio_start` con `rate`, `channels`, `sample_fmt`.
2. El Server fragmenta el audio codificado en trozos ≤ 16 KiB y envía `audio_chunk` con `seq` incremental y `ts` de envío.
3. La app decodifica y encola cada chunk para su reproducción continua.
4. Al terminar, el Server envía `audio_stop` y la app queda en silencio.

## 8. Alternative flows

### 8.1 Solo audio sin segunda pantalla (y viceversa)

- **Trigger:** el juego emite audio sin contenido de video, o video sin audio.
- **Flow:** cada stream es independiente (estados de SPEC-003 y de este SPEC no se afectan entre sí).
- **Result:** el audio se reproduce sin segunda pantalla activa, o la pantalla se muestra en silencio.

### 8.2 Corte transitorio de red

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
| Dos Android en sesión | cada uno recibe su propio stream por su conexión |
| El usuario silencia el audio en la app | se deja de reproducir localmente; los chunks siguen llegando y descartándose |

## 11. Business rules

- **R1:** el audio solo existe sobre sesión autenticada (NFR-004).
- **R2:** el orden lo determina exclusivamente `seq`.
- **R3:** los fragmentos perdidos no se retransmiten; la reproducción avanza al más reciente (coherente con R3 de SPEC-003).
- **R4:** la prioridad de streams es inputs > video > audio (ARCH §8): ante congestión, el audio se degrada antes que el video y jamás retrasa los inputs.
- **R5:** la sincronía A/V se mantiene dentro de ±150 ms; si el desfase lo excede, la app re-sincroniza descartando audio atrasado.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Mensajes de audio (`audio_start`/`audio_chunk`/`audio_stop`) | este documento §4; NDJSON según SPEC-001 §4.2 | v1 |
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
| Flujo principal (§7) y AC-1 de REQ-005 | TEST-020 |
| Reanudación tras corte (§8.2, AC-2) | compartido con TEST-017 de SPEC-003 |
| Sincronía A/V (±150 ms) y NFR-006/007 | pruebas de performance en banco físico |

## 17. Open questions

- ¿Códec de compresión para el audio (o PCM crudo es suficiente para v1 en 802.11ac)? — owner: Eng lead — 2026-10-07 — abierta
- ¿Tolerancia de jitter buffer (500 ms inicial) ajustable por el usuario? — owner: Eng lead — 2026-10-07 — abierta

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: stream de audio sobre la sesión |
