# Change Request (CR) — CR-065 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-065 |
| Title | Receptor UDP Android: búfer de socket ampliado (4 MB) para no descartar fragmentos del IDR (freezes de ~2 s y render a 0 fps) |
| Type | `Correction` |
| Status | `Applied (2026-11-03, code-applied; nota de implementación en SPEC-003 §4.4 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner («reducir microtirones/congelamientos al máximo»; medición en dispositivo) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-003` (§4.4 receptor UDP, §5 contrato de gate a IDR), `CR-062` (nota de implementación), `DES-002` |
| Sections | §4.4 (ventana/reensamblado), §5 (IDR) |
| Current version → new version | `SPEC-003 1.x.x → pendiente` |

## 2. Requested change

**Current (implementación del receptor UDP, `UdpMediaClient`):**

```text
DatagramSocket(null); reuseAddress = true; soTimeout = 100 ms; connect(...)
```

Sin `receiveBufferSize` explícito: el socket queda con `net.core.rmem_default`
del dispositivo (229 376 B en el banco de pruebas). El relay emite el AU
completo en un único lazo de `send_to` (§4.4), así que un IDR de escena pesada
llega como una ráfaga de cientos de datagramas seguidos.

**Proposed:**

```text
DatagramSocket(null); reuseAddress = true;
receiveBufferSize = 4 MiB   // antes de connect(); el kernel acota a rmem_max
soTimeout = 100 ms; connect(...)
```

Contrato sin cambios: mismas ventanas, mismos criptogramas, misma semántica de
"frame válido hasta IDR" (§5). Solo cambia la capacidad del socket para
absorber la ráfaga antes de que el lazo de recepción la drene.

## 3. Motivation

`RcvbufErrors` del kernel Android crecía de forma sostenida durante el stream
(+181 en 5 s ≈ +36 datagramas/s, 1,77 M acumulados desde el arranque). La
pérdida no era aleatoria: **caía siempre en el AU del IDR**, el único AU
grande. Consecuencias medidas en el log del receptor:

- El AU del IDR se reensamblaba incompleto y se abandonaba por la ventana de
  256 `seq` (`purged ≈ 0,7/s`, `fecOk = 0`: los grupos con pérdida en ráfaga
  pierden ≥2 miembros, fuera del alcance del XOR).
- Cada IDR perdido dejaba un hueco de exactamente 1 `fn` → `session disc` cada
  58 `fn` (2 s, la cadencia del GOP del plugin) → gate del decoder cerrado.
- `dec gate ... descartadas-esperando-idr=5850`: el gate no volvía a abrir
  nunca; render a **0 fps** aunque el receptor entregaba ~30 AU/s estables.
- En el encoder no había defecto: `idr_diag` del plugin mostraba IDR publicados
  cada ~2 s de forma continua (`since_last_idr_ms` reiniciándose).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | El objetivo de fluidez no cambia. | none |
| SPEC-003 | yes | §4.4 no fija el tamaño de búfer del socket; se añade la nota de implementación (búfer ≥ 4 MiB antes de `connect`). | update |
| ARCH | no | Sin cambios de fronteras. | none |
| DES-002 | yes | Documenta el receptor UDP del cliente. | update |
| TEST-062/063/… (CR-062) | no | Semántica intacta; el fix es una opción de socket. | none |
| Code | yes | 1 archivo, ~8 líneas (`UdpMediaClient`). | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Sin cambios de wire. | none |

## 5. Options considered

1. **Búfer de socket 4 MiB (propuesto)** — absorbe la ráfaga completa del IDR;
   cero cambios de contrato; el kernel ya permite hasta `rmem_max` (8 MiB en el
   dispositivo de prueba). Medido: `RcvbufErrors +0`, `purged=0`.
2. Pacing en el emisor del server (trocear la ráfaga con pausas) — ayuda, pero
   añade latencia al IDR y no protege de otras ráfagas (re-join, ráfagas de
   WiFi); queda como refuerzo opcional.
3. Do nothing — el AU clave muere siempre en escena pesada y la sesión se queda
   congelada hasta la siguiente reconexión.

## 6. Decision

- **Outcome:** `Approved` (por instrucción del owner y evidencia en dispositivo)
- **Rationale:** la pérdida de fragmentos era del búfer del receptor, no del
  encoder ni de la red; ampliar el búfer elimina el descarte sin tocar el
  contrato del canal.
- **Rollout:** mismo release (app Android).

## 7. Execution checklist

- [ ] SPEC-003 §4.4 actualizado (nota de búfer mínimo del receptor UDP)
- [ ] DES-002 actualizado
- [ ] Tests nuevos: no aplica (opción de socket, no verificable en JVM)
- [ ] `testing/traceability.md`: fila de CR-065 en el historial
- [x] `_index.md` actualizado
- [x] Cambio de código aplicado (`UdpMediaClient.runClient`)

## 8. Validation (banco de pruebas, 2026-11-03)

| Métrica | Antes | Después |
|---|---|---|
| `RcvbufErrors` (kernel Android) | +181 / 5 s | **+0 / 10 s** |
| `purged` (AUs abandonados) | ≈0,7 / s | **0** |
| IDR aceptados por el decoder | 0 | **16 / 30 s** (~1 cada 1,9 s) |
| `dec gate` / `session disc` | continuos (cada 58 fn) | **ninguno en 30 s** |
| fps presentados (SurfaceView) | 0 | **29,9 – 30,2** (max 41,5 ms) |

## 9. Deuda abierta

La instrumentación de diagnóstico en Android (`TwinpadUdpDia`: contadores del
reensamblador y logs de gate) se retiró antes del commit de CR-065; la causa
quedó cerrada con la medición en dispositivo (§8). La instrumentación del
plugin de Cemu (`idr_diag`/`idr_drop`/`idr_request` en `TwinpadClient.cpp`) SE
MANTIENE hasta que el owner cierre la validación en juego real y se retirará
con su propio commit del submódulo.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-03 | Buffy (agente) | Abierto y aplicado en código con medición en dispositivo |
