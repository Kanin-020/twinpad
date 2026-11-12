# Change Request (CR) — CR-100 — Twinpad

| | |
|---|---|
| ID | CR-100 |
| Title | Resume de sesión conserva el stream de la selección de juego hasta que el teléfono lo consume |
| Type | Behavior change |
| Status | Open |
| Author | Buffy (agente) |
| Created | 2026-11-12 |
| Decided | - |
| Decided by | - |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-001 (pairing/session), SPEC-003 (video stream) |
| Sections | SPEC-001 resume (`pair_resume`) y SPEC-003 inicio de stream tras `game_select` |
| Current version → new version | SPEC-001 1.4.0 → 1.5.0; SPEC-003 1.16.0 → 1.17.0 |

## 2. Requested change

> Pendiente de redactar con el texto exacto tras confirmar la causa (ver §3).

**Current:**

```text
En un resume, el servidor reenvía games_available y acepta game_select, pero el teléfono
no recibe game_selected ni stream_start en ninguno de los intentos probados. El segundo
pair_resume puede ser rechazado con E1 antes de que el primero consuma game_selected.
```

**Proposed:**

```text
El teléfono intenta abrir el canal de medios (8425) en cuanto coinciden las tres
condiciones: stream_start recibido, superficie de salida adjunta y estado Linked.
Si la superficie llega después de stream_start, el intento se lanza al adjuntarla.
Si stream_start llega después de la superficie, el intento se lanza en onStreamStart.
No hay cambio de wire ni de servidor.
```

## 3. Motivation

Evidencia (2026-11-12, `session_id=Ut1ZAGD9tuT18IfPFP2FJA`):

- Cemu codifica y envía video: `frame_sent=97` en 5 s y `TwinpadMediaDiag sent=76136 fail=0 socket=45` en `~/.local/share/Cemu/log.txt`.
- El servidor recibe `game_select id=cemu vivo=true` y envía `game_selected ok=true` en el primer intento.
- Ese primer intento termina con `Sesión finalizada. Causa: error de E/S`, sin llegar al teléfono.
- El segundo `pair_resume` recibe `games_available` y `game_select`, pero acaba en `Vinculación rechazada (E1)`.
- La app envía `game_select` y `video_config` pero no recibe `game_selected` ni `stream_start`. Sin `stream_start` no se abre el canal de medios 8425, por lo que no hay video, audio ni input.

Hallazgo posterior (2026-11-12, tras la aprobación): el stream sí se inicia. El teléfono recibe `game_selected` y `stream_start` (`11:57:26`). El canal de medios 8425 no se abre porque `connectIfReady` sale con `surface=false dims=true linked=true` en `onStreamStart`, y la superficie llega después (`setOutputSurface` a `11:59:11`). `scheduleReconnect` sólo se lanza desde `attachSurface`, así que cuando la superficie llega antes de `stream_start`, nada reintenta la conexión tras `stream_start`. La hipótesis del corte de socket del resume queda descartada para este síntoma.

Hallazgo de medios (2026-11-12, reproducido con `am start` de `PairingActivity`): la conexión UDP a 8425 nunca se establece. Cada `attachSurface` llama a `closeChannel()` antes de `connectIfReady()`, y `surfaceChanged` de `PairingActivity` dispara `attachMediaSurface` de forma repetida (`setOutputSurface` cada ~2 s). Cada intento nuevo se cierra con el anterior, y el hilo UDP del intento cerrado falla con `recvfrom failed: EBADF`, antes de que llegue el `JOIN` al servidor. Por eso el puerto 8425 no aparece conectado en `ss`.

Causa confirmada anterior: en `onStreamStart`, `scheduleReconnect()` sale en su primera línea (`expectedSurface = surface ?: return`) porque la superficie aún es `null`. El reintento nunca se lanza, y el único intento de `connectIfReady` sale sin conectar. Cuando `attachSurface` llega después, `streamStartLine` ya está fijado, así que ese camino sí programa el reintento; el fallo está en el caso contrario (superficie después de `stream_start`), que llega cuando `attachSurface` ya no corre con `streamStartLine` fijado.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-XXX | no | Sin cambio de requisito | none |
| SPEC-001 | yes | Comportamiento del resume | update 1.5.0 |
| SPEC-003 | yes | Inicio de stream tras selección | update 1.17.0 |
| ARCH | no | Sin cambio de frontera | none |
| DES-XXX | no | Sin cambio visual | none |
| TEST-XXX | yes | Requiere prueba de resume + selección | update (TEST-0192 libre) |
| Code | yes | `server/src/main.rs`, `server/src/session.rs`, `server/src/registry.rs`, `TcpPairingClient.kt` | PR único |
| Data / migrations | no | - | none |
| Integrations / contracts | no | Mismo wire, sin campos nuevos | none |

## 5. Options considered

1. Corregir la secuencia de resume y retención como se describe en §2 — la opción más directa y acotada.
2. No hacer nada — el stream no se reanuda tras reabrir la app; el usuario no puede jugar sin reiniciar el servidor.

## 6. Decision

- **Outcome:** pendiente
- **Rationale:** pendiente
- **Rollout:** pendiente

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
| 0.1.0 | 2026-11-12 | Buffy (agente) | Opened |
