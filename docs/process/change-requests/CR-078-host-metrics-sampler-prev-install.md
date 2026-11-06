# Change Request (CR) — CR-078 — Twinpad

| | |
|---|---|
| ID | CR-078 |
| Title | Fix del emisor `host_metrics` del server: `HostMetricsSampler::sample()` instalaba el previo de CPU después de `?` → no emitía nunca |
| Type | `Bugfix` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-05 |
| Decided | 2026-11-05 |
| Decided by | Product owner (petición directa: «las métricas siguen vacías») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `server/src/host_metrics.rs`, `testing/traceability.md` |
| Sections | SPEC-006 §4 (`host_metrics`, CR-076 v2); emisor por sesión (patrón CR-055) |
| Current version → new version | SIN cambios de versión (fix conforme al comportamiento YA especificado en SPEC-006 §4: delta de CPU entre dos lecturas con previo) |

## 2. Requested change (owner)

```text
Los gauges PC de la ventana de espera quedan «—» en todo momento aunque la
sesión esté viva (ping/pong OK). Diagnóstico pedido al agente con sesión
activa en banco.
```

## 3. Root cause (evidencia de banco, 2026-11-05)

1. Contadores de E/S del proceso del server en marcha
   (`/proc/<server>/io`, deltas de 10 s × 2): **82 bytes y 82 bytes** =
   exactamente dos `ping` (~41 B) por ventana de 5 s. Si el emisor
   `host_metrics` estuviera vivo esperaría ~600 B por ventana. El emisor
   no escribe: la app no puede mostrar nada que no llega.
2. Versión del binario descartada: `target/release/twinpad-server`
   (2026-11-04 17:06) es posterior al commit de CR-076 (`44369b94`,
   17:04) y contiene el código v2.
3. Wire/codec descartados: round-trip TEST-0157 verde, el variante
   `HostMetrics` del `GameMsg` coincide con el wire del
   server y con `HostMetricsCodec.kt` de la app.
4. Causa real en `HostMetricsSampler::sample()`:

```rust
let cpu_percent = self.prev.and_then(|prev| cpu_percent(prev, now))?;
self.prev = Some(now);   // ← inalcanzable en el primer tick
```

El operador `?` sobre `cpu_percent` sale de la función cuando `prev`
aún es `None` (primer tick), ANTES de la línea que instala el previo.
El previo nunca se guarda, el segundo tick vuelve a entrar por el mismo
camino y `sample()` devuelve `None` SIEMPRE; el emisor (loop con
`let Some(sample) = sampler.sample() else { continue; }` a 2 s) corre
eternamente sin emitir. El unit existente TEST-0157
(`sampler_needs_two_cpu_ticks_and_no_negative_ram`) no lo detectó
porque su cuerpo de segundo tick era condicional (`if let Some`).

## 4. Change (developer solution + update)

| | |
|---|---|
| Server | `sample()` guarda `self.prev = Some(now)` ANTES de evaluar el result del delta y devuelve la muestra con `cpu_percent.map(...)` (el primer tick queda en «instala previo y no emite», conforme a SPEC-006 §4 y al doc-header del módulo) |
| Test | TEST-0159 (unit Linux, `host_metrics.rs`): el sampler EMITE en el 2.º tick (con pausa de 300 ms entre ticks: los jiffies de `/proc/stat` requieren avance real; delta total 0 es legítimo del wire y el emisor lo salta) |
| Docs | `testing/traceability.md` fila TEST-0159 + fila CR-078; Next = TEST-0160 / CR-079 |

## 5. SPEC impact

Ninguno: el fix implementa exactamente el comportamiento ya descrito
(«CPU: delta de /proc/stat entre dos lecturas; el primer muestreo solo
instala el previo»). SPEC-006 queda sin versión nueva.

## 6. Testing evidence (banco, 2026-11-05)

- `cargo test --lib` (workspace server): **84 passed / 0 failed**
  (incluye TEST-0159 nuevo y sin las sondas temporales del diagnóstico).
- Binario release reconstruido `target/release/twinpad-server`
  2026-11-05 12:37 y server REINICIADO en banco con el fix
  (8420/8430 a la escucha; QR nuevo servido en la UI local).
- Verificación en vivo del wire prevista tras re-emparejar (la app
  mantiene la clave de sesión solo en memoria — R6 — y el reinicio del
  server cierra la sesión; el teléfono debe escanear el QR nuevo).

## 7. Residual risks

- El primer tick de cada evento de sesión no emite (por diseño: sin
  delta aún); los gauges muestran «—» ~2 s extra al vincular.
- Delta total 0 entre ticks consecutivos (dormido profundo, HZ bajo)
  se salta este tick y se recupera el siguiente (sin datos inventados).
- En Windows la medición no existe («—»): fuera de scope (limitación ya
  declarada en CR-077/CR-076 v2).
