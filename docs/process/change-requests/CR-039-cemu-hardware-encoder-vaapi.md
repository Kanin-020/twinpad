# Change Request — CR-039 — Encoder H.264 por hardware (VAAPI) en Cemu

| | |
|---|---|
| ID | CR-039 |
| Title | Usar el encoder H.264 por hardware disponible (h264_vaapi) para eliminar el cuello de botella de OpenH264 CPU |
| Type | `Behavior change` |
| Status | `Decided` (aplicación de build/infra pendiente) |
| Author | Twinpad team (agente) |
| Created | 2026-10-29 |
| Decided | 2026-10-29 |
| Decided by | Product owner (elección en conversación: «Habilitar encoder por hardware») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003 (nota de build/infra), DES-004 (ruta de encoder), traceability |
| Sections | Ruta hardware de CR-020; requisitos de la dependencia ffmpeg del build |
| Current version → new version | Propuesto; pendiente de versión al aplicar |

## 2. Requested change

**Current:**

```text
El intento de encoder hardware en Linux prueba h264_nvenc/h264_qsv/h264_amf/h264_vaapi,
pero el libavcodec enlazado por Cemu carece del backend de dispositivo VAAPI:
av_hwdevice_ctx_create(VAAPI) devuelve -1 y la sesión cae siempre a OpenH264 CPU
(~7-8 fps de emisión con pipeline de ~200 ms por frame).
```

**Proposed:**

```text
El build de Cemu enlaza una libavcodec con soporte VAAPI completo (variante
ffmpeg de RPM Fusion o ffmpeg estático propio con --enable-vaapi/--enable-libdrm).
Con la preferencia twinpad_hardware_acceleration activada, Cemu inicializa
h264_vaapi sobre /dev/dri/renderD128 y codifica el DRC en hardware; el fallback
a OpenH264 CPU sigue siendo automático si el init falla. El wire de SPEC-003
(AVC Annex B, perfil, metadata) no cambia.
```

## 3. Motivation

Medición de CR-036 (2026-10-29): OpenH264 CPU sostiene ~7-8 fps (pipeline ≈200 ms/frame); la GPU AMD Radeon integrada (RADV/Mesa 26.2) soporta H.264 por VAAPI pero el `libavcodec` del build ("libavcodec-free" de Fedora o estático sin vaapi) no trae el backend: los 4 encoders se registran pero la creación del dispositivo VAAPI falla (probe con dlopen: `av_hwdevice_iterate_types` sin VAAPI; `av_hwdevice_ctx_create` err=-1 por defecto y con `/dev/dri/renderD128`).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-003 | Maybe | Nota de infraestructura del build (qué backend se enlaza) | Updated al aplicar |
| DES-004 | Yes | Ruta de encoder hardware activa en Linux/AMD | Updated al aplicar |
| REQ-005 | No | Contrato de stream intacto | None |
| Code | No | `TwinpadClient.cpp` ya prueba h264_vaapi; sin cambio de código | None (solo build) |
| Build/infra | Yes | Rebuild de ffmpeg con VAAPI + relink de Cemu | Nuevo script/DOC |
| Docs de testing | Yes | TEST para el intento de hardware y fallback | Al aplicar |

## 5. Options considered

1. Rebuild de ffmpeg con VAAPI (RPM Fusion o estático) y relink de Cemu — **seleccionada** por el owner.
2. Bajar resolución para mantener OpenH264 CPU — rechazada por el owner.
3. Mantener CPU y cerrar solo el defecto de continuidad — rechazada (no resuelve el throughput).

## 6. Decision

- **Outcome:** Decided (2026-10-29): implementar la opción 1.
- **Rationale:** es la única opción que alcanza el objetivo del video fluido (≥30 fps) sin cambiar el wire; la GPU del host soporta VAAPI; el código ya está preparado.
- **Rollout:** al aplicar, registrar en el changelog de SPEC-003/DES-004 y en `docs/engineering` el procedimiento de build (variable del sistema o script), y ejecutar la verificación física (fps y CPU/GPU con el título).

## 7. Execution checklist

- [x] Product owner approval recorded (2026-10-29)
- [ ] Rebuild de la dependencia ffmpeg con VAAPI y relink de Cemu
- [ ] Documentar el procedimiento de build
- [ ] Tests/TEST IDs del intento hardware y fallback
- [ ] Verificación física (fps de emisión y carga CPU/GPU)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-29 | Twinpad team (agente) | Opened y decidido por el owner; evidencia del probe VAAPI |
