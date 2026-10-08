# SPEC-004 — Signature HID y flujo de inputs del Gamepad

| | |
|---|---|
| ID | SPEC-004 |
| Title | Signature HID y flujo de inputs del Gamepad (REQ-003) |
| Version | 0.1.0 |
| Status | `Draft` |
| Owner | Eng lead |
| Implements | REQ-003 (AC-1, AC-2); base para AC-3 (rumble) |
| Realized by | DES-XXX (`-` hasta asignar; ADR-003 define el medio) |
| Verified by | TEST-018, TEST-019 |
| Last updated | 2026-10-07 |

## 1. Purpose

Fija el contrato de la Signature HID (CTA-C5: la composición exacta del gamepad) y el flujo observable de inputs desde la app hasta el juego en la PC: qué elementos existen, cómo viajan y qué garantías de orden/latencia tiene el sistema (NFR-002).

## 2. Definitions

- **Signature HID**: composición fija del Gamepad de v1: 2 joysticks analógicos (X/Y con rango −127…127, centro 0), D-pad de 8 direcciones, 12 botones digitales (A/B/X/Y, L1/R1, L3/R3, Start/Select, 2 extra), triggers analógicos L2/R2 (0…255) y giroscopio de 3 ejes (opcional por dispositivo).
- **Elemento HID**: cada uno de los ítems anteriores, identificado por nombre canónico (`stick_l`, `button_a`, `trigger_r2`, `gyro_pitch`, …).
- **Input report**: la instantánea completa del estado de todos los elementos, enviada por la app cuando algo cambia.
- Ver CTX §8 para Gamepad HID, Input táctil, Input físico.

## 3. Preconditions

- Sesión establecida (SPEC-001) o canal HID operativo por Bluetooth (ADR-003).
- El SO de la PC lista el gamepad (REQ-003 AC-1) — condición de borde gestionada por el adaptador HID, no por este contrato.

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `type` | string | exactamente `"input_report"` | yes |
| `seq` | integer (uint32) | secuencial creciente por sesión; nunca se repite | yes |
| `stick_lx`, `stick_ly`, `stick_rx`, `stick_ry` | integer (int8) | −127…127; 0 = centro | yes |
| `dpad` | integer (uint8) | 0–7 (dirección) o 8 (neutro) | yes |
| `buttons` | integer (uint16) | máscara de bits de los 12 botones canónicos (bit 0 = `button_a`, … bit 11 = `extra_2`) | yes |
| `trigger_l2`, `trigger_r2` | integer (uint8) | 0–255 | yes |
| `gyro` | array de 3 × int16 | opcional; −32768…32767 por eje | no |

Canal: el transporte HID definido por ADR-003 (Bluetooth HID device role). El framing exacto del reporte es el descriptor HID estándar; este SPEC fija la semántica, no el byte layout (ese vive en DES-004 y en el descriptor).

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Input en el juego | el elemento cambiado se refleja en el juego como input del gamepad estándar | cada `input_report` válido |
| Rumble | la app hace vibrar el teléfono con intensidad/duración del evento | el juego dispara rumble (REQ-003 AC-3) |
| Gamepad visible | el SO de la PC lista el gamepad mientras exista sesión/vínculo | REQ-003 AC-1 |

## 6. States

Estados del gamepad (lado app):

- `H0 Offline`: sin canal HID operativo; los controles no envían.
- `H1 Ready`: canal operativo; los cambios de la superficie táctil/sensores generan reportes.

| From | Event | To | Side effects |
|---|---|---|---|
| `H0` | canal HID operativo (sesión/emparejamiento listo) | `H1` | se envía un reporte inicial con todos los elementos en reposo |
| `H1` | cambio de cualquier elemento | `H1` | se envía `input_report` con `seq` incrementado |
| `H1` | rumble recibido | `H1` | vibración con intensidad/duración del evento |
| `H1` | canal caído | `H0` | el SO ve el gamepad desconectado; los controles dejan de enviar |

Invariant: dentro de `H1`, dos cambios del mismo elemento siempre producen reportes con `seq` creciente; el último reporte con `seq` mayor define el estado observable del gamepad.

## 7. Main flow

1. El usuario toca un control (o mueve los sensores físicos): la app actualiza el estado del elemento.
2. La app compone el `input_report` con la instantánea completa y `seq` = último+1.
3. El reporte viaja por el canal HID y llega al SO de la PC como input del gamepad.
4. El juego consume el input sin software adicional (REQ-003 AC-2).
5. Si el juego emite rumble, la app vibra (AC-3) — reativo al evento, sin bloquear el envío de inputs (ARCH §8: inputs no bloquean por video/audio).

## 8. Alternative flows

### 8.1 Rumble desde el juego (REQ-003 AC-3)

- **Trigger:** el juego dispara un evento de vibración.
- **Flow:** el evento viaja en sentido PC → app por el canal HID (output report estándar del gamepad).
- **Result:** el teléfono vibra con la intensidad/duración pedida; los inputs siguen fluyendo durante la vibración.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| Reporte con `seq` menor o igual al último procesado | el reporte se descarta (el estado más reciente manda) | sin mensaje al usuario |
| Reporte con campo fuera de rango (p. ej. stick > 127) | el elemento fuera de rango se clampea al límite; el resto del reporte se aplica | sin mensaje |
| Canal HID cae a mitad de sesión | el gamepad aparece desconectado en la PC; la app muestra el estado de sesión de SPEC-002; los inputs no se envían hasta reconexión | estado de sesión, no error de input |
| Rumble cuando el dispositivo no soporta vibración | se ignora silenciosamente | sin mensaje |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Dos cambios del mismo botón en < 1 ms | ambos se capturan; al menos el último genera reporte (coalescing permitido siempre que el estado final viaje con `seq` mayor) |
| Stick en el centro exacto tras movimiento | reporte con valor 0 (no queda "pegado") |
| App en segundo plano | los inputs cesan (SPEC-002 §8.3 cancela el intento; el gamepad se desconecta del SO o deja de enviar) |
| Otro gamepad físico conectado a la PC | conviven: cada gamepad es un dispositivo HID independiente; el juego elige cuál usar |
| Dispositivo sin HID device role (CTX-A2) | la app lo detecta y lo informa; no aparece como gamepad |
| Gyro ausente en el dispositivo | el reporte se envía sin `gyro`; los ejes no llegan al juego |

## 11. Business rules

- **R1:** la Signature HID es fija en v1; extenderla exige CR (los juegos sin SDK dependen de ella).
- **R2:** el estado observable del gamepad lo define siempre el reporte con `seq` mayor; ningún reporte antiguo puede revertir un cambio.
- **R3:** los inputs tienen prioridad sobre video/audio (ARCH §8): ningún buffer de stream puede retrasar un `input_report` por encima del presupuesto de NFR-002 (p95 ≤ 15 ms).
- **R4:** todo elemento de la superficie táctil de REQ-008 debe mapear a exactamente un Elemento HID de la Signature; un control sin asignar no envía (ver SPEC-008 AC-3).
- **R5:** el rumble nunca bloquea ni retrasa la ruta de inputs.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Signature HID (elementos y rangos) | este documento §2/§4 | v1; extensible por CR |
| Descriptor HID del gamepad | DES-004 (por asignar) | byte layout del reporte |
| Transporte HID | ADR-003 (Bluetooth HID) | WiFi/USB excluidos en v1 |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Botón táctil llega al juego (REQ-003 AC-2)
  Given el gamepad en H1 con todos los elementos en reposo
  When el usuario presiona el botón asignado a button_a
  Then la app envía input_report con bit 0 de buttons = 1 y seq mayor
  And el juego recibe la pulsación de button_a como input del gamepad

Scenario: Rumble del juego (REQ-003 AC-3)
  Given el gamepad en H1 y el juego con rumble activado
  When el juego dispara un evento de vibración fuerte por 300 ms
  Then el teléfono vibra con esa intensidad y duración
  And los inputs siguen enviándose durante la vibración

Scenario: Reporte antiguo no revierte el estado (R2)
  Given procesado un reporte con seq=10 (button_a presionado)
  When llega un reporte con seq=9 (button_a en reposo)
  Then el reporte se descarta y button_a sigue presionado para el juego
```

## 14. Invariants

- `seq` es estrictamente creciente en los reportes aceptados.
- Cada elemento de la Signature existe exactamente una vez en cada reporte.
- El estado del gamepad en la PC siempre coincide con el último reporte aceptado.

## 15. What "complies" means

Una implementación cumple este SPEC si y solo si: respeta la Signature de §2, produce los outputs de §5 en cada condición, reproduce cada error de §9, se comporta como definen los edge cases de §10, mantiene los invariants de §14 y pasa las verificaciones de §16. Lo no definido aquí NO es requerido.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Flujo táctil → juego (AC-2) y orden por `seq` | TEST-018 |
| Rumble (AC-3) y edge cases de §10 | TEST-019 |
| Latencia NFR-002 (p95 ≤ 15 ms) | pruebas de performance en banco físico con inyección HID sintética |

## 17. Open questions

- ¿Cobertura real de HID device role por dispositivo Android 12+? (encuesta de dispositivos) — owner: Eng lead — 2026-10-07 — abierta
- ¿El rumble viaja solo por output reports de BT HID o también por la sesión LAN como respaldo? — owner: Eng lead — 2026-10-07 — abierta (se decide en DES-004)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: Signature HID y flujo de inputs |
