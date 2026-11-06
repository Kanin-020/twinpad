# Change Request — CR-086 — Anillo VRAM y medidor compacto animado

| | |
|---|---|
| ID | CR-086 |
| Title | Sustituir FAN por VRAM y compactar/animar los gauges del host |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Product owner (petición directa del usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002, SPEC-006, DES-002, `testing/traceability.md`, `core/src/game.rs`, `server/src/host_metrics.rs`, Android metrics UI/codec |
| Sections | SPEC-002 R22; SPEC-006 §4; DES-002 interfaces/flows |
| Current version → new version | SPEC-002 1.12.0 → 1.13.0; SPEC-006 1.24.0 → 1.25.0; DES-002 0.5.0 → 0.6.0 |

## 2. Requested change

**Current:** tres anillos GPU/CPU/RAM y stats CPU temp, GPU temp y FAN (% PWM de GPU); `host_metrics` contiene `fan_percent` opcional.

**Proposed:** retirar FAN de UI, sampler y wire; añadir el anillo VRAM con uso/total de memoria de video del host. La fila superior muestra GPU · CPU · RAM · VRAM; FPS permanece como texto y CPU/GPU temp permanecen como stats. El progreso de los anillos transiciona suavemente al cambiar una muestra, y se reducen márgenes/padding desperdiciados para compactar la vista.

VRAM se obtiene del par Linux DRM `mem_info_vram_used` / `mem_info_vram_total` (bytes), convertido a MiB. Ambos campos son opcionales y aparecen juntos; lectura ausente o incoherente significa «—». La fuente no disponible en otros sistemas no se inventa. La interpolación circular dura 700 ms con curva accelerate-decelerate; cada muestra nueva sustituye la transición anterior.

**v2 (segunda petición del owner):** con dato, un brillo de 36° recorre el arco lleno en sentido horario de forma continua, una vuelta cada 1800 ms, para que el medidor comunique que la lectura sigue viva aunque el valor no cambie (estilo carga de batería). El brillo solo se dibuja sobre el arco con valor —nunca sobre la pista vacía— y no corre con «—». Se detiene cuando el gauge no está visible (hoja plegada, vista oculta o actividad en segundo plano) y se reanuda al volver a mostrarse. La transición de 700 ms de cada muestra se conserva.

## 3. Motivation

El usuario confirmó que no necesita el indicador FAN y solicitó un anillo VRAM, una vista más compacta y una animación circular inspirada en el feedback de carga de batería. El host Linux observado expone `mem_info_vram_used` y `mem_info_vram_total` en `/sys/class/drm/card1/device`, por lo que VRAM es medible sin dependencias externas. El hwmon observado no expone lectura útil de ventilador.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ | no | No cambia la capacidad de métricas del host | none |
| SPEC-002 | yes | Cuarto anillo, sin FAN, panel compacto y animación al cambiar valor | Actualizar R22/version/changelog |
| SPEC-006 | yes | `host_metrics` reemplaza `fan_percent` por el par opcional `vram_used_mb`/`vram_total_mb` | Actualizar contrato/version/changelog |
| ARCH | no | Se conserva el flujo Servidor→Android existente | none |
| DES-002 | yes | Documentar campos y render de cuatro anillos con transición circular | Actualizar interfaces y versión |
| TEST | yes | Cobertura de parseo VRAM, codec y brillo de carga | TEST-0177 actualizado; TEST-0180/0181/0182 registrados; TEST-0176 superseded |
| Code | yes | Muestreador Linux DRM, wire y UI Android | Implementar en la misma iteración |
| Data / migrations | no | Muestras no persistidas; campos nuevos opcionales | none |
| Integrations / contracts | yes | Compatibilidad por campos opcionales; se elimina el campo FAN que Android tolera como desconocido | Mantener host sin VRAM como «—» |

## 5. Options considered

1. Usar el par DRM disponible del host y mostrar VRAM como anillo — dato real, verificable y sin dependencias externas.
2. Mantener FAN o mostrar una lectura inferida — no responde al usuario o inventa un porcentaje no expuesto.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** sustituir la lectura de ventilador no disponible por VRAM observable; preservar el resto de las métricas y hacer explícito el progreso de actualización sin alterar su valor.
- **Rollout:** mismo release; campos opcionales del mensaje de sesión, sin persistencia.

## 7. Execution checklist

- [x] SPEC-002, SPEC-006 y DES-002 actualizados con CR-086.
- [x] El server lee el par DRM VRAM en MiB y lo emite sin FAN.
- [x] Android muestra GPU/CPU/RAM/VRAM en anillos; FPS y temps quedan; FAN se elimina.
- [x] Cambio de progreso circular animado y retícula más compacta.
- [x] TEST-0180/0181 registrados en código y matriz; TEST-0177 actualizado y TEST-0176 marcado superseded.
- [x] `cargo fmt --check`, `clippy -D warnings`, tests Rust focalizados y test JVM del códec pasan (2026-11-06).
- [x] v2: brillo de carga continuo sobre el arco lleno, apagado sin dato o con la vista oculta (TEST-0182).
- [x] v3: padding uniforme de 12 dp en los cuatro lados del contenido del panel y márgenes internos compensados, para que la retícula y las stats queden contenidas y más juntas.

## 8. Test results

| TEST | Result |
|---|---|
| TEST-0180 | Passed — `cargo test -p twinpad-core host_metrics` (2/2, 2026-11-06) |
| TEST-0181 | Passed — `cargo test -p twinpad-server --lib host_metrics` (8/8, incluido `vram_scan_reads_and_validates_drm_counter_pair`, 2026-11-06) |
| TEST-0177 | Passed — `./gradlew :app:testDebugUnitTest --tests '*HostMetricsCodecTest*'` (2026-11-06) |
| TEST-0182 | Passed — `./gradlew :app:testDebugUnitTest --tests '*GaugeViewChargingTest*'` (4/4, 2026-11-06) |

Formato y linter: `cargo fmt --all -- --check` y `cargo clippy -p twinpad-core -p twinpad-server --all-targets -- -D warnings` sin hallazgos (2026-11-06). No se ejecutó el render en dispositivo: la verificación visual del cuarto anillo queda como banco pendiente.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto y aprobado por solicitud directa del owner |
| 0.2.0 | 2026-11-06 | Buffy (agente) | v2: brillo de carga continuo de 1800 ms sobre el arco lleno (36°), activo solo con dato y vista visible; TEST-0182 |
| 0.3.0 | 2026-11-06 | Buffy (agente) | v3: padding uniforme de 12 dp (arriba, abajo, izquierda y derecha) en el contenido del panel y márgenes internos reducidos — retícula y stats contenidas y más compactas; sin cambio de comportamiento |
