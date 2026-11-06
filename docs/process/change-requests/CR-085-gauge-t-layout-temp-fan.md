# Change Request — CR-085 — Medidor en T (anillos CPU/GPU/RAM + FPS grande + TEMP/FAN del host)

## 1. Meta

| | |
|---|---|
| Id | CR-085 |
| Title | UI del medidor en T (cómo el AYN Thor) con TEMP/FAN reales del PC |
| Scope | SPEC-002 (UI Android), SPEC-003/006 (wire `host_metrics` extensible), server |
| Owner request | «La UI del medidor es muy fea; en `reference/` está la de Aynthor — dividida en T: arriba los círculos GPU CPU RAM; FPS en grande a la izquierda (sin pastel, como los otros anillos); TEMP y % de ventilador a la derecha. TEMP se muestra AMBAS (CPU y GPU) al mismo tiempo; GPU del host es AMD.» |
| Status | Approved |

## 2. Context

La retícula de gauges (CR-076 v3: 2×2 de `GaugeView` circular CPU/GPU/RAM/FPS)
se ve pobre junto a la referencia `reference/AYN-Thor-18.jpg`: la tarjeta FPS
del AYN Thor es un número grande sin anillo, TEMP/FAN son tarjetas de texto
con valor grande, y los anillos son donas con la etiqueta dentro. Además el
owner quiere temperatura y ventilador reales: hoy el wire `host_metrics`
(CR-076 v2) solo trae CPU/GPU %, RAM MB y FPS del juego — no hay
temperaturas ni ventilador en ningún SPEC ni CR, y el server no los muestrea.

- El host corre el server en Linux: `/proc` + `/sys/class/hwmon` son la
  misma familia de ficheros que el sampler ya usa (CR-076 v2, GPU busy por
  sysfs en CR-076 v2).
- GPU del host: **AMD** (amdgpu hwmon expone `temp*_input` y `pwm1`/
  `pwm1_max` del ventilador). La CPU AMD también expone temp por hwmon
  (`k10temp`/`zenpower`); CPUs Intel exponen `coretemp`.
- La retícula sirve a DOS orígenes (CR-077: PC o teléfono Gamepad). Datos
  de TEMP/FAN solo existen para el origen PC; en origen teléfono quedan
  «—» (regla de no inventos, CR-076).
- El wire es tolerante a campos omitidos (`gpu_percent`/`game_fps` ya lo
  son): los 3 campos nuevos siguen ese patrón (ausente = sin dato).

## 3. Decision

1. **Layout en T** (`gauge_grid` en `activity_pairing.xml`): fila superior
   ancha con los tres anillos **GPU · CPU · RAM** (GaugeView, orden tal
   como lo nombró el owner).
   Fila inferior: **FPS en grande a la izquierda** — tarjeta de texto sin
   anillo (número grande + «FPS»), como la «75 FPS» de la referencia; a la
   derecha una tarjeta de stats con **CPU temp · GPU temp · FAN %** (valor
   grande + etiqueta pequeña, estilo TEMP/FAN de la referencia).
2. **Temp mostrada: CPU y GPU a la vez** (columna del derecho con las dos
   lecturas + FAN). Fuente: `/sys/class/hwmon`.
   - CPU: chip `k10temp`/`zenpower` (AMD) o `coretemp` (Intel); valor =
     máx de sus `temp*_input` (mºC → °C entero, redondo).
   - GPU: chip `amdgpu` (también se acepta `nvidia`/`nouveau` por si el
     hardware cambia); valor = máx de sus `temp*_input`.
   - FAN: del chip de GPU — `pwm1 / pwm1_max` (o 255 si no hay `pwm1_max`)
     × 100, techo 100. Sin pwm el GPU → FAN «—».
   - Chip que falte → campo ausente («—»); jamás se inventa un valor.
3. **Wire**: `GameMsg::HostMetrics` (core) gana 3 campos opcionales
   `cpu_temp_c`, `gpu_temp_c`, `fan_percent` (u8, absent = sin dato, con
   `skip_serializing_if = Option::is_none`, patrón de `gpu_percent`).
   Validación de decode: fan 0–100; temp solo u8 (0–255 °C). El server
   conserva compatibilidad: un host sin hwmon emite igual que hoy.
4. **UI**: `HostMetrics` del codec Android gana los 3 campos `Int?`;
   `WaitingGaugePanel` re-escribe el FPS grande, las dos temps y el FAN
   («—» sin dato); los `GaugeView` siguen siendo el pintor de los anillos.
   Fuente teléfono: temps y FAN siempre «—».
5. **v2 (segunda prueba del banco, 2026-11-05)**: los anillos dejan de ser grises — `GaugeView` gana el atributo XML `ringColor` (GPU azul `#4FA8FF`, CPU magenta `#D941C8`, RAM verde `#43CE7C`, referencia AYN Thor); la pista de fondo es el MISMO color al 20 % de alpha y sin dato sigue gris neutral («—»); la letra DENTRO del anillo se reduce (valor 0.2→0.16 del ancho, etiqueta 10→9 dp).
6. **v3 parcial del banco — «GPU y FAN no mandan nada»**: el busy de la GPU ya no asume `card0` — `read_gpu_busy` escanea `/sys/class/drm/card*/device/gpu_busy_percent` (TEST-0178 con fixture temporal) y después cae a los fallbacks fijos; el FAN sigue atado al pwm del chip de GPU (si el amdgpu no expone `pwm1`, queda «—»): pendiente del owner el listado de hwmon del host para decidir fuente alternativa.

7. Sin PWR ni iconos del AYN Thor: la retícula Twinpad es 5 métricas
   (CPU/GPU/RAM/FPS/TEMP/FAN) — el resto de la pantalla del AYN (modos,
   botones de ajuste) no se replica.

## 4. Change (developer solution + update)

| | |
|---|---|
| Wire | `core/src/game.rs`: `HostMetrics{cpu_temp_c, gpu_temp_c, fan_percent: Option<u8>}` + guardas y tests del round-trip |
| Server | `server/src/host_metrics.rs`: parser puro de hwmon (chips CPU/GPU, temps acumuladas, pwm→%) + sampler que los llena + línea que los emite |
| Android | `HostMetricsCodec.kt` (campos `Int?`), `activity_pairing.xml` (layout T nuevo: fila de 3 anillos GPU/CPU/RAM + fila FPS grande/stats), `WaitingGaugePanel.kt` (labels y render de las tarjetas nuevas), `strings.xml` (FAN/TEMP) |
| Tests | TEST-0175 (server: `hwmon` puros + sampler con hwmon del FS real), TEST-0176 (core: round-trip de los 3 campos nuevos), TEST-0177 (Android: decode codec con y sin temp/fan, «—» cuando faltan) |

## 5. SPEC impact

- SPEC-003: el catálogo de `host_metrics` gana 3 campos opcionales (nota
  bajo la misma regla de la CR-076: la extensión formal del catálogo queda
  por actualizar; esta CR es su autoridad temporal).
- SPEC-002/DES-002: la retícula de gauges de la ventana de espera es en T
  con las tarjetas nuevas (nota).
- SPEC-006 sin cambio duro (el mensaje no lo calcula Cemu).

## 6. Checklist

- [ ] Wire extendido sin romper el decode vigente («host_metrics» sin los campos nuevos sigue válido en las 3 esquinas).
- [ ] Server muestrea hwmon con chips tolerantes y sin datos inventados.
- [ ] Layout T en el `gauge_grid` visible en espera y slider.
- [ ] Tests novos con TEST ID + fila en traceability.
- [ ] Gates: cargo fmt/clippy/test (core+server) + gradle unidad/ktlint/detekt/assembleDebug verde.

## 7. Notes

- El duque muestrea el banco con la GPU AMD; los tests de hwmon usan los
  ficheros reales del host guardados espera tolerante sin hwmon («—»).
- El CR-085 es de UI+wire pequeño: ``sin cambio de crypto/handshake;
  el campo nuevo se parsea en el mismo lector de NDJSON de la sesión.

## 8. Test evidence

| Version | Metrica | Result |
|---|---|---|
| 1.0.0 | TEST-0175 (server): parsers hwmon + lectura REAL del host tolerante | Passed (cargo test, 2026-11-05) |
| 1.0.0 | TEST-0176 (core): round-trip/guardas de los 3 campos nuevos | Passed (cargo test, 2026-11-05) |
| 1.0.0 | TEST-0177 (app): decode codec con/sin temp+fan, rechazo fan 101 | Passed (JVM, 2026-11-05) |
| 1.0.0 | Gates | cargo fmt/clippy/test (core+server) EXIT=0; gradle testDebugUnitTest/ktlint/detekt/assembleDebug BUILD SUCCESSFUL EXIT=0 |
| 1.0.0 | Banco (pendiente del owner) | re-emparejar con la APK nueva: layout T visible en espera y slider; en STREAMING las cards de TEMP CPU/GPU y FAN deben llenar con el hwmon del host (GPU AMD); origen Gamepad = «—» |
| v2 | TEST-0178 (server): escaneo del busy de GPU en todas las tarjetas del drm (fixture temporal: cardN con `gpu_busy_percent`found y card0 sin él; solo la legible queda en la lista) | Passed (cargo test, 2026-11-05) |
| v2 | Gates | cargo fmt/clippy test (server EXIT=0); gradle testDebugUnitTest/ktlint/detekt/assembleDebug BUILD SUCCESSFUL EXIT=0; APK reinstalada |
| v2 | Banco (pendiente del owner) | anillos GPU azul / CPU magenta / RAM verde con pista del mismo color al 20 %; letra interior menor; si «GPU y FAN» siguen «—» tras rebuild+reinstall del server, pasar al owner el listado `for d in /sys/class/hwmon/*/; do echo "$D $(cat $d/name 2>/dev/null)"; done` para fijar la fuente |

## 9. History

| Version | Date | Author | CHANGE |
|---|---|---|---|
| 1.0.0 | 2026-11-05 | Buffy (agente) | Approved por el owner: layout T + TEMP CPU/GPU y FAN % del host por hwmon AMD; wire tolerante a campos omitidos |
| 2.0.0 | 2026-11-05 | Buffy (agente) | Segunda prueba del banco: anillos con color por gauge (ringColor XML + pista tenue) y letra interior menor; «GPU y FAN no mandan nada»: busy de GPU escaneado en todas las tarjetas (card1+), FAN pendiente del listado hwmon del host |
