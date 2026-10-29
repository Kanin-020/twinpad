# Change Request — CR-035 — Micrófono del teléfono hacia Cemu

| | |
|---|---|
| ID | CR-035 |
| Title | Enviar audio del micrófono del teléfono a Cemu como entrada de micrófono del VPAD |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Twinpad team (usuario + agente) |
| Created | 2026-10-28 |
| Decided | 2026-10-29 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-005 (audio), SPEC-006, DES-004, traceability |
| Sections | SPEC-006 mensajes Android → Cemu; SPEC-005 formato de audio |
| Current version → new version | SPEC-006 1.19.0 → 1.20.0; SPEC-005 1.3.0 → 1.4.0; DES-004 §4 |

## 2. Requested change

**Current:**

```text
In VPADController::VPADRead, the Twinpad branch keeps m_mic_active = false. Cemu receives no microphone audio from the phone. Android only has audio playback (AudioPlayback.kt) for Cemu → phone audio.
```

**Proposed:**

```text
El micrófono es BAJO DEMANDA (pedido explícito del owner, espejo de CR-038). El HLE `mic` de Cemu emite `mic_request on=true/false` en las transiciones MICOpen/MICClose del juego (solo DRC0); el Servidor lo reenvía a Android y el teléfono solo captura micrófono PCM s16le mono 32 kHz (AudioRecord, permiso RECORD_AUDIO) mientras el juego «cemu» esté seleccionado Y la demanda vigente. La captura se envía como `mic_chunk` (samples 32–32000, data base64url de 2·samples bytes s16le). Cemu valida estrictamente cada chunk, mantiene una cola s16 FIFO drop-old de 1 s (32000 muestras @ 32 kHz) y `mic_updateOnAXFrame` consume 96 muestras (3 ms) SOLO si la cola está fresca (≤ 250 ms) y la demanda vigente; sin ellas los caminos existentes de Cemu (dispositivo del PC / blow mic) siguen sirviendo el micrófono.
```

## 3. Motivation

Los juegos que usan el micrófono del Wii U GamePad no reciben audio del teléfono. Cemu ya tiene el micrófono HLE completo (`Cafe/OS/libs/mic/mic.cpp`: MICInit/MICOpen/MICClose con demanda, ring buffer s16 y `mic_updateOnAXFrame` alimentado por frame de AX), así que el camino HLE no necesita APIs nuevas de VPAD: solo falta inyectar el PCM del teléfono.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Yes | Nueva capacidad del Plugin (micrófono DRC vía teléfono) | Confirmado por el owner |
| SPEC-005 | Yes | Nueva dirección/formato de PCM (teléfono → Cemu, s16le mono 32 kHz) | Reglas añadidas (1.4.0) |
| SPEC-006 | Yes | Mensajes nuevos `mic_chunk` y `mic_request` | Añadidos (1.20.0) |
| DES-004 | Yes | Camino micrófono Cemu: cola drop-old + gancho en HLE `mic` | Actualizado |
| TEST | Yes | Codec/routing/captura/cola | TEST-0101/0102/0103/0104 |
| Code | Yes | Captura Android, mensajes core/server/plugin, gancho `mic` de Cemu | Completado |
| Integraciones / contratos | Yes | Nuevo mensaje + permiso `RECORD_AUDIO` | Version bump (SPEC-006 1.20.0) |

## 5. Options considered

1. **Micrófono bajo demanda pilotado por el HLE `mic` (aprobado por el owner)** —
   on/off con las transiciones MICOpen/MICClose del juego; formato s16le mono
   32 kHz resuelto por la constante `MIC_SAMPLES_PER_3MS_32KHZ` y la API HLE
   existentes (no hay exportaciones VPAD de micrófono que implementar).
2. Captura constante mientras el juego está seleccionado — más simple, pero
   graba sin permiso de juego/micrófono abierto; rechazado por el owner («el
   micrófono también es bajo demanda, es importante»).
3. Do nothing — los juegos con micrófono del GamePad no reciben entrada.

## 6. Decision

- **Outcome:** `Approved` y aplicado por el owner (usuario, 2026-10-29):
  «El microfono también es bajo demanda, es importante».
- **Rationale:** demanda = lo que el juego realmente hace (MICOpen/MICClose);
  sin `mic_request` no hay captura ni permiso solicitado en el teléfono.
- **Rollout:** misma iteración; wire compatible hacia atrás (mensajes
  desconocidos se descartan como hoy).

## 7. Execution checklist

- [x] Product owner approval recorded (bajo demanda, 2026-10-29)
- [x] Target document(s) updated (SPEC-006 1.20.0, SPEC-005 1.4.0, DES-004)
- [x] Tests added/updated (TEST-0101, TEST-0102, TEST-0103, TEST-0104)
- [x] `testing/traceability.md` rows updated
- [x] `_index.md` updated
- [x] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Twinpad team (agente) | Opened |
| 1.0.0 | 2026-10-29 | Twinpad team (usuario + agente) | Applied: micrófono bajo demanda (MICOpen/MICClose), SPEC-006 1.20.0, SPEC-005 1.4.0, TEST-0101/0102/0103/0104 |
