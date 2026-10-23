# Change Request (CR) — CR-020 — Twinpad

| | |
|---|---|
| ID | CR-020 |
| Title | Aceleración por hardware del encoder H.264 de Cemu |
| Type | Behavior change |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-23 |
| Decided | 2026-10-23 |
| Decided by | Product owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003, ARCH, DES-004, testing/traceability.md |
| Sections | SPEC-003 §§4.3, 11, 16; ARCH §§2, 4, 8; DES-004 §§1, 3, 4, 7, 8, 10, 14 |
| Current version → new version | SPEC-003 1.4.0 → 1.5.0; ARCH 1.0.0 → 1.1.0; DES-004 1.1.5 → 1.2.0 |

## 2. Requested change

**Current:** Cemu convierte el DRC a I420 y codifica H.264/AVC mediante OpenH264 en CPU; el perfil, el relay cifrado y MediaCodec Android están definidos en SPEC-003 v1.4.0 y CR-014.

**Approved:** Cemu presenta en sus configuraciones una preferencia persistente `twinpad_hardware_acceleration`, desactivada por defecto. Si está activada, intenta inicializar un encoder H.264 hardware disponible para la plataforma/build, con AVC Constrained Baseline, nivel 4.2, 4:2:0, sin B-frames y salida Annex B con SPS/PPS en cada IDR. Si no existe un encoder hardware utilizable o la inicialización/configuración inicial falla, Cemu inicializa OpenH264 en CPU con los parámetros del contrato actual. El cambio de preferencia reinicia el encoder en el siguiente frame/configuración vigente. Las dimensiones y el FPS configurados no se reducen ni cambian como fallback; el IPC, el wire de SPEC-003, el bitrate objetivo y la resolución permanecen iguales. El servidor continúa como relay opaco y Android usa el mismo MediaCodec/Surface.

## 3. Motivation

TEST-057 observó entre 0.98 y 3.77 fps en el flujo Cemu→Android, y el pipeline medido ya identifica la etapa del encoder. La opción permite reducir trabajo de CPU en equipos con encoder H.264 hardware sin sacrificar resolución ni compatibilidad en equipos que carezcan de soporte. La aceleración es opt-in hasta que las mediciones físicas de rendimiento, latencia y compatibilidad confirmen su comportamiento.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/REQ-005/REQ-006 | Revisado | El origen de medios, la sesión y la presentación no cambian | Ninguna |
| SPEC-003 | Sí | OpenH264 deja de ser el único encoder; se conserva el perfil y wire | Actualizar §4.3, reglas y verificación mediante CR-020 |
| ARCH | Sí | Encoder opcional hardware en Cemu; fallback CPU | Actualizar componentes, comunicación y rendimiento |
| DES-004 | Sí | Preferencia local persistente, inicialización hardware y fallback | Actualizar diseño y flujos |
| TEST | Sí | Verificar bitstream/perfil, preferencia default y fallback | Reservar TEST-082 e inscribir en traceability |
| Code | Sí | Cemu, wx config/UI, FFmpeg y fallback OpenH264 | Implementación en esta iteración |
| Data / migrations | No | Campo boolean XML con default false; configuración existente se conserva | Ninguna |
| Integrations / contracts | Sí | FFmpeg se configura con encoder hardware opcional por plataforma | Build incluye los backends redistribuibles disponibles; no cambia IPC/wire |

## 5. Options considered

1. **Hardware opcional con fallback OpenH264 CPU** — aprobada; reduce carga CPU cuando existe soporte y mantiene compatibilidad sin alterar resolución.
2. **Mantener solo OpenH264 CPU** — conserva el contrato actual, pero no permite usar capacidad de encoder hardware del PC.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** La preferencia opt-in evita cambios para usuarios existentes; inicialización hardware se intenta primero, y OpenH264 mantiene el flujo si el hardware no está disponible o no puede inicializarse. Ambas rutas producen el mismo contrato H.264, sin degradación automática de resolución.
- **Rollout:** Cemu actualizado; no requiere migración de sesión, servidor o Android. La prueba E2E/performance física sigue siendo necesaria para afirmar una tasa de FPS concreta.

## 7. Execution checklist

- [x] DES-004 y ARCH actualizados con CR-020; SPEC-003 v1.5.0 conserva explícito el contrato de bitstream.
- [x] TEST-082 añadido y registrado en `testing/traceability.md`.
- [x] Preferencia persistente, encoder hardware con fallback CPU y opciones de build integrados.
- [ ] Gates de Cemu y prueba del encoder con backend GPU disponible ejecutados; prueba física Cemu/Android pendiente si no hay título activo.
- [x] Preferencia de comportamiento aprobada por el product owner (2026-10-23).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-23 | Product owner + Twinpad team | CR-020 abierto y aprobado para implementación |
