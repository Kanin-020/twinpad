# Change Request (CR) — CR-045 — Twinpad

> ⚠️ TEMPLATE — the ONLY mechanism to change an `Approved` document. One CR per logical change.

> **What this is:** a controlled, reviewable change to an approved artifact (REQ, SPEC, ARCH, DES, guidelines, strategy). It records what, why, impact and decision.
> **What it is NOT:** a bug report (use the issue tracker) or a discussion thread — keep it decidable.

| | |
|---|---|
| ID | CR-045 |
| Title | Cola de muestras de motion pendientes: drenaje completo por frame del VPAD (los tirones del gyro) |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Buffy (agent) |
| Created | 2026-10-30 |
| Decided | 2026-10-30 |
| Decided by | Twinpad team |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 |
| Sections | §Mapeo de muestras inerciales AndroidDSU a Cemu DSU (fila de `processMotionSample`), changelog |
| Current version → new version | `1.12.1 → 1.13.0` |

## 2. Requested change

**Current:**

```text
`timestamp_us` determina Δt, que debe ser positivo y ≤200 ms. Se alimenta
`WiiUMotionHandler`, que genera MotionSample fusionado para VPADStatus
```

El estado de motion enviado por la app (AndroidDSU, p. ej. 30 Hz = 33 ms/muestra)
se mantiene en un único estado «último reporte» (`TwinpadClient::m_inputState`).
`VPADController::update_motion` se ejecuta una vez por frame del juego
(`VPADRead`) y solo consume ese último estado: si entre frames llegaron varias
muestras, las intermedias se descartan y el dt usado es el que va del último
procesado al último recibido.

**Proposed:**

```text
`timestamp_us` determina Δt por muestra (positivo y ≤200 ms). Cada muestra de
motion recibida se encola en `TwinpadClient` (cola FIFO acotada, 32 slots,
protegida por `m_inputMutex`) y `VPADController::update_motion` drena la cola
completa en cada frame: procesa todas las muestras pendientes en orden por
`timestamp_us`, cada una con su propio Δt, a través de `WiiUMotionHandler`,
y publica el estado fusionado una vez al final del drenaje. Las muestras que
llegan cuando la señal no está fresca se descartan sin procesar (la cola se
limpia en el rama «waiting»), igual que hoy se resetea el estado fusionado.
```

## 3. Motivation

Categoría: implementation discovery / defect perceptual. Evidencia capturada en
vivo con el log TWINPAD_MOTION_LOG (instrumentación temporal de Cemu,
2026-10-30):

- El teléfono emite motion a ~30 Hz (33 ms/muestra; seq consecutivos cada ~33 ms
  en `[TwinpadGyro]`).
- En una ventana de captura de 77 s con repro de los tirones, Cemu procesó 1987
  de 3564 muestras emitidas: **pérdida del 44 %** (dt mediano 49.6 ms, p90
  79 ms, máximo 134 ms); en una segunda captura, 44 % igual (seq 82028→85591,
  3564 emitidas, 1987 procesadas).
- La actitud/euler post-fusión es suave (0 saltos >7.2°/muestra en la mayoría de
  la captura; 10 en la ventana con repro agitado), o sea que la fusión Mahony es
  correcta por muestra; el patrón defectuoso es la **cadencia con la que el
  juego consume las muestras**.
- Síntoma reportado: tirones de cámara en el juego apuntando hacia abajo;
  los giros rápidos avanzan a mitad de resolución y de forma irregular (1 muestra
  en unos frames, 2–4 coalescidas en otros).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 motion | no | El wire format y la semántica de muestras no cambian | none |
| SPEC-004 | no | La cadena Android→Cemu DSU sigue intacta (CR-037) | none |
| SPEC-006 | no | El canal plugin no cambia | none |
| ARCH | no | Cola interna de Cemu, sin nuevos procesos ni firewalls | none |
| DES-004 | yes | Fila de `processMotionSample` y changelog | update |
| TEST-084 | no | El mapeo no cambia | none |
| TEST-111/112 (nuevos) | yes | Drenaje en orden + descarte en rama «waiting» | add |
| Code (Cemu) | yes | `TwinpadClient.h/.cpp` (cola + drenaje), `VPADController.cpp` (drenaje por frame) | PR único |

## 5. Options considered

1. **Cola acotada + drenaje completo por frame (propuesta)** — elimina la
   pérdida cadencial sin añadir latencia (la cola se vacía en cada frame; en
   régimen estable hay 0–1 muestras pendientes); el Mahony ya integra con el dt
   real de cada muestra, así que la ganancia es exactamente la fidelidad de
   cadencia del gyro ante el juego.
2. Do nothing — el juego sigue viendo dt 25–134 ms con el 44 % de las muestras
   descartadas; los giros rápidos quedan a mitad de resolución (tirones).
3. Elevar el rate de VPADRead — no controlable: depende del frame rate del
   juego; no resuelve los frames lentos.

## 6. Decision

> The approver fills this.

- **Outcome:** `Approved`
- **Rationale:** La captura en vivo demostró la pérdida del 44 % de las muestras de motion entre recepción y VPAD con dt 25–134 ms (tirones del gyro al apuntar abajo); la cola acotada con drenaje por frame conserva la cadencia completa sin tocar el wire format ni la fusión por muestra.
- **Rollout:** `same release` (aplicado junto con la verificación en vivo: descarte 44 % → 1.4 %, 0 saltos de orientación)

## 7. Execution checklist

- [x] DES-004 actualizado (versión bump a 1.13.0, changelog con CR-045)
- [x] TEST-0111 y TEST-0112 añadidos y registrados en `testing/traceability.md`
- [x] `docs/process/change-requests/_index.md` actualizado (estado)
- [x] Código: cola en `TwinpadClient`, drenaje en `VPADController::update_motion`
- [x] Verificación en vivo: pérdida 44 % → 1.4 % con el log de captura full-rate (dt mediano 24.8 ms, 0 saltos de orientación en el repro)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-30 | Buffy (agent) | Opened |
| 1.0.0 | 2026-10-30 | Twinpad team | Approved y Applied (cola implementada, TEST-0111/0112 verdes en release, verificación en vivo 1.4 %) |
