# Change Request (CR) — CR-103 — Twinpad

| | |
|---|---|
| ID | CR-103 |
| Title | Reanudar la sesión (`pair_resume`) debe reactivar el audio del juego (`audio_start`/`audio_chunk`), no solo el vídeo y los inputs |
| Type | `Behavior change` |
| Status | `Decided` (opción A, cambio de decisión 2026-11-20) |
| Author | Buffy (agente) |
| Created | 2026-11-20 |
| Decided | 2026-11-20 |
| Decided by | Usuario |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-001` (§8.2, §4.2), `SPEC-003` (§4.4/§8.5 si el audio viaja por el relay), `DES-001` (§reanudación) |
| Sections | SPEC-001 §8.2 (reanudación), SPEC-003 (audio), DES-001 (reanudación) |
| Current version → new version | `SPEC-001 1.5.1 → 1.6.0`; `SPEC-003 1.17.0 → 1.18.0`; `DES-001 0.3.0 → 0.4.0` |

## 2. Requested change

> Pendiente de decisión (ver §5). El texto exacto se redactará tras elegir la opción.

**Current:**

```text
pair_resume restaura la sesión de control y la selección de juego retenida (CR-100).
El audio del juego (game_audio_start / audio_start, audio_chunk) no se contempla
explícitamente en la reanudación: si el plugin no reenvía game_audio_start, el
servidor no emite audio_start y la app no reproduce sonido.
```

**Proposed (opción A, recomendada):**

```text
Tras aceptar pair_resume, si hay un juego seleccionado con capability "audio",
el servidor reemite audio_start (rate, channels) a la app y reanuda el reenvío de
audio_chunk desde la fuente PCM vigente. Si el plugin no tiene fuente PCM activa,
el servidor no emite audio_start y la app muestra el estado sin audio.
```

## 3. Motivation

Evidencia observada en vivo (2026-11-20):

- Tras cerrar y reabrir la app, el servidor registra `Sesión finalizada. Causa: EOF`
  y luego `Vinculación exitosa: session_id=4so95VHtCRszkR_Pc3IqIQ` (reanudación con
  el mismo session_id). El vídeo vuelve por UDP y los inputs llegan con retraso.
- No aparece ninguna línea de `game_select`, `game_selected`, `audio_start` ni
  `audio_chunk` tras la reanudación.
- `server/src/games.rs::start_audio` rechaza un `game_audio_start` nuevo mientras
  `audio_connection_active` siga en `true`, y ese flag solo se limpia en
  `handle_audio_connection` al cerrarse el canal 8422.
- En el plugin (`integrations/cemu/src/Cafe/Twinpad/Client/TwinpadClientInputLoop.cpp`),
  el canal de audio (8422) solo se cierra y se reabre en la rama de reconexión del
  primario. Tras el resume el primario no cae (gyro y vídeo siguen en el log de
  Cemu), así que `ConnectAudio()` y `game_audio_start` no se reenvían.

Causa probable: el audio depende de un `game_audio_start` que el plugin no vuelve a
enviar tras una reanudación del lado de la app. Pendiente de confirmar con un log
del plugin en ese momento.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | `yes` | Criterio de aceptación de reanudación debe incluir audio | `update` |
| SPEC-001 | `yes` | §8.2 debe describir el audio tras `pair_resume` | `update` |
| SPEC-003 | `yes` | Si el audio viaja por el relay tras reanudar | `update` |
| DES-001 | `yes` | Secuencia de reanudación | `update` |
| TEST-XXX | `yes` | Test de reanudación con audio (`resumedSessionPlaysAudioAfterPairResume` cubre solo el lado app) | `update` |
| Code (server) | `yes` | `games.rs`, `main.rs` (`spawn_game_audio_forwarding`) según opción | `PR server` |
| Code (plugin) | `yes` | `TwinpadClientInputLoop.cpp` según opción | `PR cemu` |
| Data / migrations | `no` | Sin cambios de datos | `none` |
| Integrations / contracts | `yes` | El plugin debe seguir enviando `game_audio_start` tras reconexión | `version bump` |

## 5. Options considered

1. **A — El servidor reemite `audio_start` tras `pair_resume`** (recomendada) — el
   servidor ya conoce la fuente PCM vigente; no depende de que el plugin reenvíe nada
   y el cambio queda en un lado.
2. **B — El plugin fuerza la reapertura de 8422 al reanudar la sesión de control** —
   corrige la causa en el plugin, pero obliga a que el plugin detecte la reanudación.
3. Do nothing — el audio no vuelve tras reabrir la app hasta reiniciar Cemu.

## 6. Decision

- **Outcome:** Opción A: tras aceptar `pair_resume`, si hay un juego seleccionado con fuente PCM activa, el servidor reemite `audio_start` a la sesión nueva. Si no hay fuente PCM, no se emite `audio_start` y la app no reproduce.
- **Rationale:** El usuario eligió la opción A tras la decisión inicial de B. Las mediciones muestran que la selección se restaura en la sesión nueva (`main.rs`, CR-056) y que la fuente PCM sigue viva en el servidor, así que el servidor puede reemitir `audio_start` sin depender del plugin.
- **Causa observada (medida, 2026-11-20):** tras reabrir la app, la conexión de audio 8422 con Cemu sigue viva y el servidor envía PCM (4 113 pops), pero la sesión nueva no recibe `audio_start`, porque el emisor solo lo manda cuando cambia la selección. La app descarta los chunks mientras su estado es `INACTIVE` (`AudioStateMachine.validChunkPcm`).
- **Corrección de la causa (medida, 2026-11-23, reabriendo solo la app):** el servidor **sí** reemite `audio_start` (`[DBG-AUDIO] audio_start emitido a sesion game=cemu rate=48000 ch=2`) al aceptar `pair_resume`, así que el diagnóstico de 2026-11-20 queda desmentido para ese camino. La causa real es del lado de la app: tras reabrirla la selección de juego arranca en `null`, y la primera selección restaurada disparaba `GameSelectionController.clearMediaForSelectionChange()` → `AudioStateMachine.reset()`, que dejaba el audio `INACTIVE` y descartaba los chunks vivos.
- **Resuelto sin cambiar el contrato:** `GameSelectionController.onGameSelected` solo limpia medios si había una selección previa (`previous != null && previous != gameId`). Medición posterior a la corrección: `reset() desde` = 0, chunks rechazados = 0, `track creado=true rate=48000 ch=2`. Confirmado por el usuario.
- **Rollout:** No requiere cambio en `SPEC-001`/`SPEC-003`: no se modifica ni el contrato de reanudación ni el de audio. Esta CR queda `Decided` como registro; el arreglo se documenta en el código y en la fila de trazabilidad de la corrección.

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [ ] Indexes (`_index.md`) updated
- [ ] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-20 | Buffy (agente) | Opened |
