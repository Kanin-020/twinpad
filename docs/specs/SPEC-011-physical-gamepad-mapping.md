# SPEC-011 — Reconocimiento y ajuste de controles físicos por mando

| | |
|---|---|
| ID | SPEC-011 |
| Title | Reconocimiento y ajuste de los controles de un mando físico, con perfiles por fabricante y modelo |
| Version | 0.2.6 |
| Status | `Approved` |
| Owner | Product owner |
| Implements | REQ-003 |
| Realized by | `-` (DES pendiente de asignar) |
| Verified by | TEST-0209, TEST-0210, TEST-0212, TEST-0217 |
| Last updated | 2026-11-26 |

> Decisiones del owner (chat, 2026-11-25): el perfil pertenece a **fabricante + modelo** y admite **perfiles propios nombrados por el usuario**, guardados; la reasignación se hace tocando la entrada y pulsando el botón **o moviendo el eje**; el cambio se **aplica y guarda al instante**; se **permiten duplicados** (una misma entrada física puede activar varias entradas canónicas); el acceso está **en Ajustes y se abre al detectar un mando nuevo**.

## 1. Purpose

Fijar cómo la app reconoce los controles que emite un mando físico conectado al teléfono, cómo aplica el mapeo por defecto de SPEC-004 §8.1 y cómo el usuario ajusta ese mapeo y lo guarda en perfiles propios por fabricante y modelo.

## 2. Scope

**Incluye:**

- Detección de un mando físico conectado por Bluetooth HID o USB y de su fabricante/modelo.
- Presentación de todas las entradas canónicas de la Signature (SPEC-004 §4) con el mapeo vigente.
- Reasignación de una entrada tocándola y pulsando un botón o moviendo un eje del mando.
- Creación, selección y guardado de perfiles nombrados por el usuario para un fabricante y modelo.
- Aplicación del mapeo al traducir `KeyEvent`/`MotionEvent` a `input_report` (SPEC-004 §8.1).

**No incluye:**

- El editor de controles táctiles (SPEC-008): otra feature, otro objeto; no se retira ni se sustituye.
- El mapeo por defecto de mandos sin perfil (SPEC-004 §8.1), que este SPEC reutiliza sin cambiarlo.
- La Signature, el wire, el HID de la PC y el canal BT HID (SPEC-004, ADR-009).
- El giroscopio: no es reasignable.

## 3. Definitions

- **Mando físico**: `InputDevice` de Android con `SOURCE_GAMEPAD` o `SOURCE_JOYSTICK`.
- **Identidad de modelo**: `vendorId` + `productId` + `name` del `InputDevice` (CR-116); si los ids son 0 y no hay nombre, se usa `descriptor`. Dos mandos del mismo fabricante y modelo comparten identidad.
- **Perfil de mando**: conjunto nombrado de correspondencias entrada física → entrada canónica, guardado para una identidad de modelo. Un modelo puede tener varios perfiles y uno activo.
- **Perfil predeterminado**: el que no existe; aplica el mapeo de SPEC-004 §8.1.
- **Entrada canónica**: cada elemento ajustable de la Signature (§4).
- **Entrada física**: un `keyCode` de `KeyEvent`, una **combinación** de `keyCode`s pulsados a la vez (CR-123) o un eje de `MotionEvent` (por id de eje) con su sentido.
- Ver CTX §8 (Gamepad HID, Input físico).

## 4. Inputs

| Input | Tipo | Constraints | Origen |
|---|---|---|---|
| Mando conectado/desconectado | evento del sistema | `SOURCE_GAMEPAD` o `SOURCE_JOYSTICK` | Android |
| `KeyEvent` del mando | `keyCode` + acción | solo con el mapeador abierto o en sesión vinculada | Android |
| `MotionEvent` del mando | valores de ejes | ejes X, Y, Z, RX, RY, RZ, BRAKE, GAS, LTRIGGER, RTRIGGER, HAT_X, HAT_Y, THROTTLE | Android |
| Acción «abrir mapeador» | evento de usuario | solo con un mando conectado | Ajustes (drawer) |
| Acción «tocar entrada» | evento de usuario | entrada canónica listada en §7 | mapeador |
| Nueva entrada física | botón o combinación de botones pulsados, o eje movido | durante la captura de una entrada | mando |
| Acción «nuevo perfil» | evento de usuario | nombre no vacío, sin saltos de línea | mapeador |
| Acción «seleccionar perfil» | evento de usuario | perfil existente del modelo o predeterminado | mapeador |

**Entradas canónicas ajustables** (todas las de la Signature salvo el giroscopio): 12 botones (`A`, `B`, `X`, `Y`, `L1`, `R1`, `L3`, `R3`, `Start`, `Select`, `extra_1`, `extra_2`), 4 ejes de stick (`stick_lx`, `stick_ly`, `stick_rx`, `stick_ry`), 2 gatillos (`L2`, `R2`) y 4 direcciones de cruceta (`up`, `down`, `left`, `right`).

> **Nota (CR-120, 2026-11-26):** `extra_1` es el botón **Home** del mando
> (Guide de Xbox / PS del DualShock), se rotula «Home» en el mapeador y su
> keycode físico estándar es `KEYCODE_BUTTON_MODE` (110), que el mapeo
> predeterminado asigna a esa fila. `L3` y `R3` usan `KEYCODE_BUTTON_THUMBL`
> (106) y `KEYCODE_BUTTON_THUMBR` (107).

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Mapeo aplicado | `input_report` de SPEC-004 §4 con los valores de las entradas físicas según el mapeo activo | mando conectado y sesión vinculada |
| Perfil activo | nombre del perfil del modelo, o «Predeterminado» | siempre visible en el mapeador |
| Perfil guardado | persistido en el almacén local de la app, asociado a la identidad de modelo | tras cada captura o cambio de perfil |
| Entrada canónica listada | etiqueta y mapeo vigente (entradas físicas o «Sin asignar») | mapeador abierto |

## 6. States

| From | Event | To | Side effects |
|---|---|---|---|
| M1 Mapeador cerrado | mando conectado cuya identidad no tiene perfiles ni aviso previo | M2 Mapeador abierto | se aplica el mapeo predeterminado, se abre una vez y se persiste la marca del modelo |
| M1 | acción «abrir mapeador» con mando conectado | M2 | se carga el perfil activo del modelo o el predeterminado |
| M2 | tocar una entrada | M3 Capturando | la fila queda resaltada; se ignoran otras acciones de captura |
| M3 | botón, combinación de botones o eje válido | M2 | la entrada física reemplaza las anteriores de esa fila canónica, se aplica y se guarda al instante |
| M3 | 10 s sin ninguna entrada física | M2 | se descarta la captura; el mapeo vigente permanece |
| M3 | tocar «cancelar» | M2 | sin cambios |
| M3 | mando desconectado | M1 | se descarta la captura; el mapeo vigente permanece |
| M2 | cambiar de perfil | M2 | se aplica y se recuerda como activo del modelo |
| M2 | crear perfil | M2 | perfil nuevo (copia del mapeo vigente), activo y guardado |
| M2 | mando desconectado | M1 | el mapeador se cierra sin cambios pendientes |

Invariante: la captura nunca bloquea la sesión ni el `input_report`; el mapeador no crea ni cierra la sesión (misma filosofía que SPEC-002 U4 y CR-108).

## 7. Behavior

1. Al conectar un mando, la app calcula su identidad de modelo y busca sus perfiles. Si no tiene ninguno aplica el mapeo predeterminado (SPEC-004 §8.1) y abre el mapeador **solo la primera vez que se detecta ese modelo**; la marca de aviso se persiste y sobrevive a reinicios y reconexiones, así que las detecciones posteriores no lo abren. Si ya tiene perfiles, aplica el perfil activo y tampoco lo abre.
2. El drawer ofrece «Mando físico» con el modelo conectado y el perfil activo; sin mando conectado la acción está deshabilitada.
3. El mapeador lista todas las entradas canónicas de §4 con su mapeo vigente. Los duplicados permitidos son entre filas: una misma entrada física puede alimentar varias entradas canónicas; una fila conserva la última entrada capturada.
4. Al tocar una fila se entra en captura. En una fila de botón se acumulan las teclas pulsadas a la vez y la asignación ocurre al soltarlas todas: la combinación más amplia observada es la entrada; una tecla suelta sigue asignando un único botón (CR-123). En el resto de filas, el siguiente eje movido por encima de 0.5 (en valor absoluto) o la siguiente pulsación de botón se asigna. Para un eje se guarda el eje y el sentido del movimiento; para un botón, su `keyCode`; para una combinación, sus `keyCode`s. El mapeador muestra los segundos que quedan y la espera se reinicia con cada entrada; a los 10 s sin entrada la captura se descarta (CR-124).
5. Al asignar, el cambio se aplica al instante al mando conectado y se guarda en el perfil activo. Si el perfil activo es «Predeterminado», se crea automáticamente un perfil llamado `Perfil 1` para ese modelo antes de guardar.
6. «Nuevo perfil» pide un nombre, crea una copia del mapeo vigente y la deja activa.
7. Cambiar de perfil aplica su mapeo y lo recuerda como activo del modelo.
8. El mapeo activo se usa al traducir eventos del mando en sesión: el botón se activa si alguna de sus entradas físicas está accionada, y una combinación exige que **todas** sus teclas estén pulsadas (CR-123); el stick usa el eje y sentido asignados con la zona muerta 0.1; el gatillo usa el eje asignado (0…1 → 0…255) o el botón (255); la cruceta compone direcciones con las entradas asignadas, igual que SPEC-004 §8.1.
9. Sin mando conectado no se consume ningún evento ni se aplica mapeo.

## 8. Errors

| Code | Condition | Observable behavior |
|---|---|---|
| E1 | Captura sin ninguna entrada física válida | no cambia nada; la fila sigue en captura |
| E2 | Perfil ilegible o corrupto | se descarta ese perfil y se aplica el predeterminado; el mapeador lo indica |
| E3 | Nombre de perfil vacío | no se crea; el diálogo permanece |
| E4 | Mando desconectado durante la captura | se descarta la captura, se cierra el mapeador y se conserva el mapeo |

## 9. Acceptance criteria

- AC-1: la primera vez que se detecta un mando sin perfiles se aplican las equivalencias automáticas de SPEC-004 §8.1 y el mapeador se abre una vez; las detecciones posteriores del mismo modelo no lo abren automáticamente.
- AC-2: tocar una fila y pulsar un botón (o varios a la vez) reemplaza esa entrada y el juego recibe el nuevo valor sin reiniciar la sesión.
- AC-3: tocar una fila y mover un eje asigna ese eje con su sentido.
- AC-4: el cambio se aplica y persiste al instante; al reabrir la app el perfil conserva el cambio.
- AC-5: una entrada física puede activar varias entradas canónicas (duplicados) sin error.
- AC-6: un modelo con varios perfiles recuerda el activo; «Nuevo perfil» copia el mapeo vigente.
- AC-7: sin mando conectado no hay captura ni consumo de eventos.
- AC-8: una combinación de botones (p. ej. `LB`+`RB`) se guarda como una sola entrada de su fila, sobrevive a reabrir la app y solo activa la entrada canónica cuando todas sus teclas están pulsadas.
- AC-9: sin ninguna entrada durante 10 s la fila deja de capturar (el contador llega a 0) y el mapeo vigente no cambia.

## 10. Observability

- El mapeador muestra el modelo, el perfil activo, por fila las entradas asignadas y, durante la captura, los segundos restantes antes de descartarla (CR-124).
- Los cambios de perfil se registran con el id de modelo (sin datos personales).
- La marca de primera detección se guarda por identidad de modelo en el almacén local de perfiles.

## 11. Verification

| Aspect | Verified by |
|---|---|
| Mapeo predeterminado, captura de botón/eje, duplicados y composición de la cruceta | TEST-0209 |
| Perfiles por fabricante/modelo: guardado, activo, nuevo perfil y corrupción | TEST-0210 |
| Combinación de botones en una fila, guardado en el códec y caducidad de la captura | TEST-0217 |
| Autoapertura única por modelo, persistida entre instancias | TEST-0212 |

## 12. Open questions

- Ninguna: OQ-1…OQ-7 cerradas por las decisiones del owner (2026-11-25) reflejadas en §3–§7.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Draft inicial: alcance, D-1/D-2 y preguntas abiertas |
| 0.2.0 | 2026-11-25 | Product owner + Buffy (agente) | Approved (decisión del owner, chat 2026-11-25): identidad fabricante+modelo, perfiles nombrados, captura de botón o eje, guardado al instante, duplicados, acceso en Ajustes y autoapertura |
| 0.2.1 | 2026-11-25 | Buffy (agente) (CR-116) | §3: la identidad usa `vendorId`/`productId`/`name` porque `InputDevice` no expone fabricante/producto como texto |
| 0.2.2 | 2026-11-25 | Buffy (agente) (CR-117) | §6 M3 y §7.3: capturar reemplaza la entrada de esa fila; los duplicados son entre filas (AC-2) |
| 0.2.3 | 2026-11-25 | Buffy (agente) (CR-118) | §6 M1, §7.1 y AC-1: la autoapertura ocurre una sola vez por modelo y se persiste; no se repite al reiniciar ni al reconectar |
| 0.2.4 | 2026-11-26 | Buffy (agente) (CR-120) | §4: `extra_1` = Home con `KEYCODE_BUTTON_MODE` y rótulo «Home»; L3/R3 con `BUTTON_THUMBL`/`BUTTON_THUMBR` |
| 0.2.6 | 2026-11-26 | Buffy (agente) (CR-124) | §6 M3, §7.4, §9 AC-9 y §10: la captura caduca a los 10 s sin entrada y el mapeador muestra los segundos restantes |
| 0.2.5 | 2026-11-26 | Buffy (agente) (CR-123) | §3, §4, §6 M3, §7.4, §7.8 y §9: una fila de botón acepta una combinación pulsada a la vez, asignada al soltarla y activa solo con todas sus teclas |
