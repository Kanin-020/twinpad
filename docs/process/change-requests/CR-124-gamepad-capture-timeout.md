# Change Request (CR) — CR-124 — Twinpad

| | |
|---|---|
| ID | CR-124 |
| Title | La captura del mapeador caduca a los 10 s sin entrada y muestra la cuenta atrás |
| Type | Enhancement |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Product owner (chat: «si no entra input en 10 segundos de un botón este queda sin input, y debes mostrar el contador que cuanto tiempo queda») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-011 §6 (M3), §7, §9, §10 |
| Sections | §6, §7, §9, §10 |
| Current version → new version | `0.2.5 → 0.2.6` |

## 2. Requested change

**Current (0.2.5):** una fila en captura permanece así indefinidamente hasta
que llega un botón/eje, se pulsa «Cancelar» o se desconecta el mando.

**Proposed:**

```text
§6 M3: si pasan 10 s sin ninguna entrada física, la captura se descarta
      y se vuelve a M2 conservando el mapeo vigente.
§7.4: el contador de la captura se reinicia con cada entrada recibida y el
      mapeador muestra los segundos restantes.
§9 AC-9: sin entrada durante 10 s la fila deja de capturar y el juego sigue
      con el mapeo anterior.
```

## 3. Motivation

Pedido del owner: una captura olvidada dejaba el mapeador bloqueado en
«Pulsa un botón…» sin ninguna señal de tiempo, y con él los eventos del mando
consumidos por la ventana de captura.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-011 | yes | §6 M3, §7.4, §9 AC-9, §10 | update vía este CR |
| Code | yes | `hid/CaptureCountdown` (puro) y cuentagotas del mapeador (`Handler` de 1 s) | implementado |
| Strings | yes | `gamepad_mapper_capturing_seconds` con los segundos restantes | hecho |
| Codec / wire / HID | no | sin cambios | none |
| Tests | yes | TEST-0217 amplía con la caducidad del contador (JVM) | actualizado |

## 5. Decision

- **Outcome:** 10 s sin entrada → fin de la captura; contador visible
  (`· N s`) que se reinicia con cada entrada.
- **Rollout:** local; sin wire.

## 6. Execution checklist

- [x] Code applied (`hid/CaptureCountdown.kt`, `ui/GamepadMapperDialog.kt`)
- [x] TEST-0217 actualizado
- [x] SPEC-011 0.2.6
- [x] Traceability actualizada

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y decidido con la petición del owner |
