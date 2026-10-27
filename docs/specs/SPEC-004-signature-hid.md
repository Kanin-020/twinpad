# SPEC-004 — Signature HID y flujo de inputs del Gamepad

| | |
|---|---|
| ID | SPEC-004 |
| Title | Signature HID y flujo de inputs del Gamepad (REQ-003) |
| Version | 1.9.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-003 (AC-1, AC-2); base para AC-3 (rumble) |
| Realized by | DES-002, DES-004 (CR-005/CR-016/CR-017/CR-018/CR-019/CR-021, Draft) |
| Verified by | TEST-018, TEST-019, TEST-043, TEST-052, TEST-059, TEST-060, TEST-078, TEST-079, TEST-083, TEST-084, TEST-087 |
| Last updated | 2026-10-24 |

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
| `motion` | object | opcional; snapshot inercial descrito abajo; si aparece, ambos vectores y el timestamp son obligatorios; campos adicionales no permitidos | no |
| `motion.timestamp_us` | integer (uint64) | máximo de los timestamps monotónicos de acelerómetro y giroscopio en microsegundos; estrictamente creciente entre snapshots de movimiento que Cemu integra | yes si `motion` está presente |
| `motion.accel` | array de 3 × float32 finitos | aceleración Android en m/s², orden X/Y/Z del sensor; longitud exactamente 3 | yes si `motion` está presente |
| `motion.gyro` | array de 3 × float32 finitos | velocidad angular Android en rad/s, orden X/Y/Z del sensor; longitud exactamente 3 | yes si `motion` está presente |
| `touch_x`, `touch_y` | integer (uint16) | posición normalizada sobre la imagen DRC, 0…65535; al soltar se conserva la última coordenada | yes |
| `touch_pressed` | boolean | true mientras hay contacto con la imagen DRC | yes |

Canal inicial: sesión LAN autenticada de SPEC-001, NDJSON (ADR-007). El Servidor conserva el último `input_report` aceptado por sesión y lo reenvía como snapshot al Plugin seleccionado conforme a SPEC-006; no requiere que exista selección previa para retener el estado. El SDK del Plugin expone polling del snapshot más reciente. El adaptador Cemu aplica el estado recibido directamente al GamePad Wii U emulado, no al gamepad del sistema operativo. Bluetooth HID nativo queda diferido. Su alcance y su formato de giroscopio se definirán en un CR posterior; hasta entonces no forma parte del contrato de movimiento. La referencia ADR-003 queda sujeta a CR-024. El adaptador Cemu mapea A/B/X/Y, L1/R1, L3/R3 y Start/Select a sus controles homónimos; ignora los dos botones extra; L2/R2 activan ZL/ZR cuando su valor es >0; sticks, D-pad y touch se mapean directamente. Android captura TYPE_ACCELEROMETER y TYPE_GYROSCOPE a SENSOR_DELAY_GAME; cada actualización de sensor produce un reporte con las muestras más recientes de ambos sensores y `timestamp_us` igual al máximo timestamp monotónico de esos eventos, convertido de nanosegundos a microsegundos. `motion` se omite hasta disponer de una muestra válida de ambos sensores; si falta cualquiera de ellos, el estado anterior se invalida y se envía sin `motion`. Cada snapshot enviado contiene muestras finitas y completas. Cemu integra únicamente timestamps estrictamente crecientes; si el intervalo supera 200 ms, no integra esa muestra. Al aplicar el snapshot al VPAD Player 1 de Twinpad, Android envía accel `(-x, -z, y)` y gyro `(x, -z, y)` ya en el marco AndroidDSU de referencia (CR-022); Cemu convierte unidades (gyro ×0.9, accel ×9.8066/100) y alimenta `WiiUMotionHandler` con accel Y/Z negadas y gyro sin cambios, igual que su ruta DSU; su salida fusionada actualiza los datos de movimiento del VPAD. La ruta local de movimiento Cemu permanece activa cuando Twinpad no es el input seleccionado. El monitor Android puede mostrar orientación local del sensor; el monitor Cemu muestra la muestra recibida y el resultado VPAD fusionado. CR-021 supersede la interpretación de orientación absoluta y la ganancia VPAD 0.5× de CR-019.

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

1. El usuario toca un control, acciona un elemento del mando físico conectado al teléfono (CR-028, §8.1) o mueve el dispositivo: la app actualiza el estado del elemento (fusión por elemento cuando intervienen ambos orígenes); si hay acelerómetro y giroscopio, captura el snapshot inercial más reciente con timestamp monotónico.
2. La app compone el `input_report` con la instantánea completa y `seq` = último+1.
3. El reporte viaja por la sesión autenticada LAN al Servidor PC; el Servidor retiene el snapshot más reciente y lo envía por IPC localhost solo al Plugin confirmado para esa sesión, conforme a SPEC-006.
4. Para una sesión con Plugin seleccionado, el Plugin consume el snapshot más reciente mediante el polling de SPEC-006. La ruta Bluetooth HID nativa hacia el SO de la PC permanece como objetivo separado de ADR-003; no sustituye el contrato del Plugin de esta entrega.
5. Si el juego emite rumble, la app vibra (AC-3) — reativo al evento, sin bloquear el envío de inputs (ARCH §8: inputs no bloquean por video/audio).

## 8. Alternative flows

### 8.1 Mando físico conectado al teléfono (CR-028)

- **Trigger:** en `H1` se recibe una tecla o movimiento de un gamepad físico conectado al teléfono (fuentes `SOURCE_GAMEPAD`/`SOURCE_JOYSTICK` de Android).
- **Flow:** `PairingActivity` traduce el evento a la Signature (keycodes canónicos de `android.view.KeyEvent`: A/B/X/Y, L1/R1, L2/R2 como trigger analógico, L3/R3, Start/Select, teclas D-pad y hat AXIS_HAT_X/HAT_Y; sticks X/Y y Z/RZ con zona muerta 0.1; triggers BRAKE/LTRIGGER y GAS/RTRIGGER) y un mapper puro produce los valores de la Signature. El mapper se fusiona con el estado de la superficie táctil por elemento: el elemento accionado físicamente manda (stick ≠ 0 tras la zona muerta, D-pad ≠ neutro, trigger > 0); en reposo manda la superficie táctil; `buttons` es el OR de ambas; touch e inercia viajan siempre desde la superficie. El reporte combinado entra al flujo único de §7 (mismo `seq`).
- **Result:** el elemento del mando físico se refleja en el juego como input del gamepad estándar; keycodes fuera de la Signature no se consumen; sin sesión operativa los eventos se ignoran.

### 8.2 Rumble desde el juego (REQ-003 AC-3)

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
| Gamepad físico conectado al teléfono (CR-028) | sus elementos se fusionan con la superficie táctil por elemento (§8.1); reposo físico no pisa el valor táctil; `buttons` acumula ambos orígenes |
| Dispositivo sin HID device role (CTX-A2) | la app lo detecta y lo informa; no aparece como gamepad |
| Acelerómetro o giroscopio ausente | el reporte se envía sin `motion`; el VPAD no integra muestras inerciales incompletas |
| Diagnóstico de movimiento Android/Cemu (CR-021) | Android muestra orientación local estimada; Cemu distingue aceleración/velocidad angular recibidas y orientación calculada por fusión VPAD; los diagramas conservan yaw verde, pitch rojo y roll azul |
| CR-016/CR-017/CR-021: diagnóstico visual del movimiento | Android muestra su orientación local estimada; Cemu muestra las muestras inerciales recibidas y el estado fusionado del VPAD. Ambos monitores son opt-in, locales y no alteran el reporte |

## 11. Business rules

- **R1:** la Signature HID es fija en v1; extenderla exige CR (los juegos sin SDK dependen de ella).
- **R2:** el estado observable del gamepad lo define siempre el reporte con `seq` mayor; ningún reporte antiguo puede revertir un cambio.
- **R3:** los inputs tienen prioridad sobre video/audio (ARCH §8): ningún buffer de stream puede retrasar un `input_report` por encima del presupuesto de NFR-002 (p95 ≤ 15 ms).
- **R4:** todo elemento de la superficie táctil de REQ-008 debe mapear a exactamente un Elemento HID de la Signature; un control sin asignar no envía (ver SPEC-008 AC-3).
- **R5:** el rumble nunca bloquea ni retrasa la ruta de inputs.
- **R6 (CR-028):** el mando físico conectado al teléfono es una fuente adicional de los mismos elementos de la Signature, no un segundo gamepad: su estado se fusiona con la superficie táctil por elemento (§8.1) y viaja en el mismo reporte con `seq` único; keycodes fuera de la Signature no se consumen.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Signature HID (elementos y rangos) | este documento §2/§4 | v1; extensible por CR |
| Transporte de la primera implementación | ADR-007 / SPEC-001 §4.2 | Sesión LAN autenticada; servidor entrega snapshots al Plugin seleccionado mediante SPEC-006 |
| Descriptor HID del objetivo final | DES-005 (por asignar) | Bluetooth HID de ADR-003 |
| Retención y entrega al Plugin | SPEC-006 §4/§7 | snapshot más reciente por sesión, polling SDK; estados intermedios coalescibles |

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
| Campos touch normalizados, roundtrip del snapshot inercial Android→Server | TEST-043, TEST-083 |
| Remapeo de orientación local al marco de pantalla para monitor Android | TEST-059, TEST-060 |
| Integración de muestras inerciales con la fusión VPAD de Cemu y aislamiento de la ruta local | TEST-084 |
| Captura Android, wire de muestras inerciales y monitor local/fusionado (CR-021) | TEST-060, TEST-078, TEST-079, TEST-083, TEST-084 |
| Rumble (AC-3) y edge cases de §10 | TEST-019 |
| Latencia NFR-002 (p95 ≤ 15 ms) | pruebas de performance en banco físico con inyección HID sintética |
| Snapshot input conservado y sondeable por Plugin | TEST-052 |
| Snapshot de input retenido antes de selección y polling del Plugin | TEST-052 |

## 17. Open questions

- ¿Cobertura real de HID device role por dispositivo Android 12+? (encuesta de dispositivos) — owner: Eng lead — 2026-10-07 — abierta
- ¿El rumble viaja solo por output reports de BT HID o también por la sesión LAN como respaldo? — owner: Eng lead — 2026-10-07 — abierta (se decide en DES-004)
- ¿El transporte de `input_report` en la primera implementación es el canal HID Bluetooth de ADR-003 o la sesión LAN de SPEC-001? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): la primera implementación usa la sesión LAN persistente de SPEC-001 (mismo canal NDJSON que SPEC-003); la vía Bluetooth HID nativa (ADR-003) es la meta final y entra por iteración/ADR posterior.**

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: Signature HID y flujo de inputs |
| 1.0.0 | 2026-10-07 | Eng lead (owner) | Approved: transporte inicial por sesión LAN de SPEC-001 (ver §17); BT HID nativo queda como meta (ADR-003) |
| 1.2.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Retención por sesión del último input y polling SDK para entregarlo al Plugin seleccionado |
| 1.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-011) | Precisa yaw/pitch/roll en el marco de la pantalla; esquema wire sin cambios |
| 1.7.0 | 2026-10-26 | Product owner + Twinpad team (CR-022) | El mapeo de ejes AndroidDSU vive solo en Android; Cemu solo convierte unidades y aplica a accel Y/Z el mismo signo que su ruta DSU |
| 1.4.0 | 2026-10-22 | Product owner + Twinpad team (CR-016) | Añade monitores opt-in de orientación en Android/Cemu y define mapper de orientación al VPAD; sin cambio wire |
| 1.5.0 | 2026-10-22 | Product owner + Twinpad team (CR-017) | Mueve el monitor Cemu a una ventana externa redimensionable; sin cambio wire |
| 1.5.1 | 2026-10-22 | Product owner + Twinpad team (CR-018) | Aclara que Android envía la orientación absoluta mostrada y alinea los colores semánticos de ambos monitores; sin cambio wire |
| 1.5.2 | 2026-10-22 | Product owner + Twinpad team (CR-019) | Reduce a 0.5× el gyro que Cemu aplica al VPAD, sin cambio del valor absoluto wire ni de los monitores |
| 1.6.0 | 2026-10-24 | Product owner + Twinpad team (CR-021) | Sustituye orientación absoluta wire por muestras gyro/acelerómetro con timestamp y fusión nativa WiiUMotionHandler→VPAD; CR-019 queda supersedido |
| 1.8.0 | 2026-10-27 | Product owner + Twinpad team (CR-023) | Bluetooth HID nativo queda diferido; el giroscopio para terceros va por SDK Twinpad (SPEC-006); sin cambio wire |
| 1.9.0 | 2026-10-27 | Product owner + Twinpad team (CR-028) | Mando físico conectado al teléfono: mapper puro (keycodes/sticks/triggers/hat) y fusión por elemento con la superficie táctil; sin cambio wire (§8.1, R6, TEST-087) |
