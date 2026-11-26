# Change Request (CR) — CR-123 — Twinpad

| | |
|---|---|
| ID | CR-123 |
| Title | Una fila del mapeador acepta una combinación de botones |
| Type | Enhancement |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Product owner (chat: «Ahora puedes permitir una combinación de botones? para asignar un solo botón?») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-011 §3, §4, §6 (M3), §7, §9 |
| Sections | §3, §4, §6, §7, §9 |
| Current version → new version | `0.2.4 → 0.2.5` |

## 2. Requested change

**Current (0.2.4):**

```text
§7.4: «La siguiente pulsación de botón o el siguiente eje movido por encima
de 0.5 se asigna a esa fila. […] Para un botón, su keyCode.»
§7.8: «el botón se activa si alguna de sus entradas físicas está pulsada»
```

Solo admite una tecla por fila: no hay forma de asignar «LB+RB» a `Home`.

**Proposed:**

```text
§4/§7.4: durante la captura de una fila de botón se acumulan las teclas
pulsadas a la vez y la asignación ocurre al soltarlas todas; la combinación
más amplia observada es la entrada. Una tecla suelta sigue asignando un
único botón (sin cambio de comportamiento). Gatillos y cruceta mantienen
una sola tecla o un eje.
§3: la entrada física puede ser una tecla, una combinación de teclas o un eje.
§7.8: la entrada de botón se activa si alguna de sus entradas físicas está
accionada; una combinación exige que todas sus teclas estén pulsadas.
```

## 3. Motivation

El mando del owner (EasySMX M05) reserva para su firmware la tecla Home/FN
(«cambia de modo Xbox ↔ D-input»), así que la única forma de tener Home es
asociarla a una combinación de botones normales. Además el mando reporta sus
dos botones traseros como `BUTTON_A`/`BUTTON_B`, sin códigos propios, de modo
que tampoco sirven como entradas nuevas.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-011 | yes | §3, §4, §6 M3, §7.4, §7.8, §9 AC-2 y AC-8 | update vía este CR |
| Code | yes | `PhysicalInput.Combo`, `ChordCapture`, `captureKeys`, códec `c:` y evaluador | implementado |
| Codec | yes | nuevo token `c:<k>+<k>`; los documentos anteriores (`k:`, `a:`) siguen leyéndose igual | compatible |
| Tests | yes | TEST-0209 ampliado, TEST-0217 nuevo | actualizado |
| Wire / HID / PC | no | la combinación produce el mismo bit canónico de la Signature | none |

## 5. Decision

- **Outcome:** combinación capturada al soltar todas las teclas, guardada como
  `c:<keyCode>+<keyCode>` y activa solo con todas pulsadas. Límite práctico de
  la captura: lo que el mando reporte; si el firmware no emite una tecla no se
  puede usar.
- **Rollout:** local; sin wire ni cambios en la PC.

## 6. Execution checklist

- [x] Code applied (`hid/GamepadMapping.kt`, `hid/ChordCapture.kt`, `hid/GamepadProfiles.kt`, `hid/PhysicalGamepadAlgo.kt`, `ui/GamepadMapperDialog.kt`)
- [x] TEST-0217 y TEST-0209 actualizados
- [x] SPEC-011 0.2.5
- [x] Traceability actualizada

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y decidido con la petición del owner |
