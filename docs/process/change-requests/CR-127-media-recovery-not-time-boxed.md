# Change Request (CR) — CR-127 — Twinpad

| | |
|---|---|
| ID | CR-127 |
| Title | Recuperación del canal media no vitalicia: reintento espaciado mientras el `stream_start` siga vigente |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner (petición directa: «Abre un CR y aplica la recuperación de video no vitalicia en PairingMediaSession») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-003` |
| Sections | `§6` (tabla de estados), `§8.2`, `§9` |
| Current version → new version | `1.17.0 → 1.18.0` |

## 2. Requested change

**Current:**

```text
§6  | `V2` | Pairing finalizado o recuperación ≥10 s | `V0` | libera recursos; estado de sesión según SPEC-002 |
§9  | recuperación del canal media ≥10 s | permanece Pairing activo pero stream pasa a V0 | indicador "sin segunda pantalla activa" |
§8.2 Flow: Android conserva `session_id` y `session_key` solo en memoria, reconecta a `server_addr:8425`,
     genera `nonce_c` fresco y repite §4.2.
```

**Proposed:**

```text
§6  | `V2` | recuperación ≥10 s | `V2` | mantiene el último frame y la Surface; reintento espaciado
    cada 10 s mientras siga vigente el `stream_start` (CR-127) |
§6  | `V2` | Pairing finalizado o `stream_stop` | `V0` | libera recursos; estado de sesión según SPEC-002 |
§9  | recuperación del canal media ≥10 s | permanece Pairing activo, stream en V2 con el último frame
    y reintento espaciado cada 10 s hasta handshake+IDR o `stream_stop` | indicador de reconexión, no error final |
§8.2 + **Reintento tras la ventana densa (CR-127):** si a los 10 s no hubo handshake ni IDR, Android
     mantiene el stream en V2 con el último frame y repite el intento cada 10 s mientras siga vigente
     el mismo `stream_start`; `stream_stop` o el fin del Pairing lo terminan.
```

## 3. Motivation

Diagnóstico en vivo del 2026-11-26 sobre la sesión activa (Cemu → servidor → teléfono):

- Cemu seguía enviando video al servidor por 8426 a **~9.8 Mbps** (+7 321 450 B en 6 s) y el servidor lo ingestaba.
- El teléfono conservaba **un solo socket**, el TCP de control 8420 (audio a 2.1 Mbps); **ninguno** hacia 8425.
- `dumpsys netstats` mostraba 1.22 GB recibidos por la app: el video había funcionado y después se perdió.
- `dumpsys SurfaceFlinger --list` no tenía **ninguna** capa `SurfaceView` de la app.

Cadena del defecto, en código:

1. Cualquier corte del canal (socket media, >2 s sin AU) deja el stream en `INTERRUPTED`; `applyStreamState` oculta `video_view` porque no es `STREAMING` y el `Surface` se destruye → `attachMediaSurface(null)`.
2. `PairingMediaSession.finishRecovery` borraba `dimensions` y `streamStartLine` a los 10 s y pasaba a `INACTIVE`; sin `Surface` y sin `stream_start` no hay reintento posible.
3. El servidor (`server/src/video.rs::run_selected_game_stream`) sólo reanuncia `stream_start` si cambia `(w,h,fps)` o si vuelve a haber frame tras un `stream_stop`; con Cemu publicando sin parar, no hay reanuncio.
4. Resultado: audio (canal distinto) sigue sonando y el video no vuelve nunca hasta reiniciar la sesión o cambiar de resolución.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 / REQ-005 | no | AC-2 ya exige reanudar sin re-pairing; el cambio no lo contradice | none |
| SPEC-003 | yes | §6, §8.2 y §9: la recuperación deja de caducar a los 10 s | update (este CR) |
| ARCH | no | Mismos componentes y canales (8425, handshake §4.2) | none |
| DES-003 | no | No describe la ventana de recuperación de la app | none |
| TEST | yes | TEST-0224: cadencia pura `MediaReconnectSchedule` | new row |
| Code | yes | `PairingMediaSession` (bucle de reintento), `PairingActivity` (conservar la Surface en V2), `stream/MediaReconnectSchedule` (nuevo) | ~40 líneas |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Wire intacto: mismo `stream_start` y handshake media | none |

## 5. Options considered

1. **Reintento espaciado sin caducidad + conservar la Surface en V2** — corrige el defecto con el wire actual: el servidor pide IDR a cada suscriptor nuevo (§8.2) y el canal revive solo.
2. **Pedir reanuncio del `stream_start`** — requeriría un mensaje nuevo teléfono→servidor (no existe en el wire; misma limitación anotada en CR-083).
3. Do nothing — el video queda muerto para siempre tras un corte de 10 s, con audio sonando.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el reanuncio no existe en el wire y el handshake media sí: mantener el estado anunciado y reintentar es la única vía compatible con el contrato. La Surface debe sobrevivir a `INTERRUPTED` porque es requisito del canal.
- **Rollout:** misma release; sin migración ni cambio de protocolo.

## 7. Execution checklist

- [x] Target document(s) updated (versión 1.18.0, changelog con este CR)
- [x] Dependent documents updated (ninguno afectado)
- [x] Tests added/updated (TEST-0224)
- [x] `testing/traceability.md` rows updated (incluida la tabla de CRs)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (si aplica)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado por petición directa del owner; aplicado en SPEC-003 1.18.0 y código |
