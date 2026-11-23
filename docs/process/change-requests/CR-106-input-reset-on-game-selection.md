# Change Request (CR) — CR-106 — Twinpad

| | |
|---|---|
| ID | CR-106 |
| Title | Al seleccionar Cemu el servidor envía `input_reset` y el plugin reinicia el filtro de `seq` de input |
| Type | New behavior |
| Status | Applied |
| Author | Buffy |
| Created | 2026-11-23 |
| Decided | 2026-11-23 |
| Decided by | Owner (petición directa del usuario: "opción 1") |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 (protocolo servidor → plugin), SPEC-004 (nota de `seq` por sesión de juego) |
| Sections | SPEC-006: mensajes servidor → plugin; SPEC-004 §9 (descarte por `seq`) |
| Current version → new version | SPEC-006 `<versión vigente> → <versión + 0.1>`; SPEC-004 `<versión vigente> → <versión + 0.1>` (ver nota de versión en CR-105) |

## 2. Requested change

**Current:**

```text
El plugin descarta todo input_report con seq <= al último aceptado. Ese estado
sólo se borra al desconectarse el bucle de input del plugin. Si la app se cierra
y se vuelve a abrir con el mismo plugin vivo, el teléfono reinicia seq en 0 y
todos los reportes nuevos se descartan (vídeo y audio sí se recuperan porque el
servidor les reenvía video_config y video_force_idr al seleccionar el juego).
```

**Proposed:**

```text
Al seleccionar el juego "cemu" (el mismo punto donde se envía video_config),
el servidor envía al plugin una línea:

  {"type":"input_reset"}

El plugin, al recibirla, pone su filtro de seq en estado inicial
(m_hasInputSeq = false) bajo m_inputMutex. El siguiente input_report aceptado
define de nuevo el estado. No se persiste: sin plugin vivo no hay efecto.
```

## 3. Motivation

Evidencia de campo (2026-11-23): tras cerrar y abrir la app con Cemu vivo, el
input no volvía. Con la traza `DBG-INPUT-SEQ` del plugin se vio:

```
[DBG-INPUT-SEQ] descartado seq=47  guardado=3190 n=48
[DBG-INPUT-SEQ] descartado seq=999 guardado=3190 n=1000
```

El servidor seguía reenviando (`DBG-SRV-INPUT ok=true`) y el socket 8421 estaba
abierto. La causa es el reinicio de `seq` en el teléfono (`GamepadStateMachine.onChannelReady`)
frente a un filtro del plugin que sólo se reinicia en reconexión del socket.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-XXX | no | Sin cambio de requisito funcional | none |
| SPEC-004 | yes | §9: aclarar que el filtro de `seq` del consumidor se reinicia al seleccionar juego | update (versión) |
| SPEC-006 | yes | Nuevo mensaje `input_reset` servidor → plugin | update (versión) |
| ARCH | no | Mismo canal NDJSON | none |
| DES-004 | no | Sin cambio visible | none |
| TEST-XXX | yes | Test servidor: `input_reset` sólo llega a Cemu vivo | new test (TEST ID + traceability) |
| Code | yes | `server/src/games.rs` (método), `server/src/main.rs` (envío al seleccionar), `TwinpadClientInputLoop.cpp` (parseo) | PR único |

## 5. Options considered

1. Reinicio explícito con `input_reset` (elegida): mismo patrón que `video_config`/`video_force_idr`.
2. Continuar `seq` desde el último enviado en el teléfono: no toca el plugin, pero exige persistir el último `seq` y cambia la semántica de SPEC-004 §R2.

Riesgo de la opción 1: un reporte viejo que llegue después del reset puede
aceptarse. Se acota porque el reset sólo ocurre al seleccionar juego, no a
mitad de sesión por cambios de calidad.

## 6. Decision

- **Outcome:** Approved por petición directa del owner; pasa a Applied cuando el código y la verificación en vivo estén hechos.
- Las versiones de SPEC-004 y SPEC-006 quedan pendientes de fijar por el owner, igual que el conflicto de versión de CR-104/CR-105.

## 7. Verification

| Item | Status | Evidence |
|---|---|---|
| Test servidor `input_reset` sólo llega a Cemu vivo | Passed | TEST-0202 `input_reset_reaches_only_live_cemu` (`cargo test`) |
| Compilación plugin | Passed | `ninja -C integrations/cemu/build Cemu_release` exit 0 |
| Verificación en vivo tras reabrir la app | Passed | Tras reabrir la app con Cemu seleccionado: último `DBG-INPUT-SEQ` descartado a 12:47:42 (`guardado=2559`); a las 12:51 el plugin acepta `cemu_rx seq=30` y `seq=40`, y el servidor reenvía input a Cemu (`DBG-SRV-INPUT ok=true`) |
