# Change Request — CR-039 — Encoder H.264 por hardware (VAAPI) en Cemu

| | |
|---|---|
| ID | CR-039 |
| Title | Usar el encoder H.264 por hardware disponible (h264_vaapi) para eliminar el cuello de botella de OpenH264 CPU |
| Type | `Behavior change` |
| Status | `Applied` (pendiente de verificación física del usuario) |
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

**Current (diagnóstico corregido 2026-10-29):**

```text
El libavcodec estático de vcpkg que enlaza Cemu (62.28.101) YA tiene VAAPI
completo (registra h264_vaapi y crea el device con vaGetDisplayDRM) y el enlace
de Cemu ya trae -lva-drm/-lvpl/-lstdc++. El bloqueo real está en el driver
VA-API del sistema: la variante GNU/libre de Fedora (mesa-dri-drivers) solo
expone H264ConstrainedBaseline con entrypoint VLD (decodificar); sin
VAEntrypointEncSlice, avcodec_open2 transforma "No usable encoding profile
found" (-38) y la sesión cae siempre a OpenH264 CPU (~7-8 fps).
```

**Proposed (ya aplicado):**

```text
1. Runtime: instalar el driver VA-API con encode de RPM Fusion Free
   (mesa-va-drivers-freeworld) — candidato válido el 26.2.5 fc44. Con él,
   vainfo expone EncSlice (VAEntrypointEncSlice=6) para H264 Main/High/HEVC.
2. Código (TwinpadClient.cpp): si av_hwdevice_ctx_create(VAAPI) falla una vez,
   exporta LIBVA_DRIVERS_PATH (directorio va-freeworld junto al ejecutable +
   /usr/lib64/dri-freeworld) y reintenta; se registra con
   "[TwinpadVideo] VAAPI device creation failed; retrying with freeworld drivers".
3. El driver freeworld extraído se copia a integrations/cemu/bin/va-freeworld
   para el caso sin instalación del sistema.
Constraints del driver: encode H.264 solo con 128 ≤ w,h ≤ 4096 (el DRC a
854×480 respeta el rango). El wire de SPEC-003 (AVC Annex B, perfil,
metadata) no cambia; el fallback a OpenH264 CPU sigue siendo automático.
```

## 3. Motivation

Medición de CR-036 (2026-10-29): OpenH264 CPU sostiene ~7-8 fps (pipeline ≈200 ms/frame). Sondas reproducibles del 2026-10-29 (mismo toolbx Fedora 44, GPU AMD Radeon Graphics RADV RENOIR):
- Con las librerías estáticas de vcpkg (`-lavcodec -lavutil -lva-drm -lvpl`): device VAAPI se crea (err=0) y el contexto de frames (NV12, pool 8) inicializa OK.
- Encode de prueba con las opciones exactas de `CreateHardwareH264Encoder` (CBR, constrained_baseline): con el driver libre de Fedora falla en `avcodec_open2` (-38, "No usable encoding profile found"); `vaQueryConfigEntrypoints` solo muestra VLD.
- Con el driver freeworld de RPM Fusion (mismo directorio vía `LIBVA_DRIVERS_PATH`): `avcodec_open2` OK, contrato respetado (perfil/level/bframes) y packet H.264 keyframe emitido a 1280×720.
- El directorio `/usr/lib64/dri-freeworld` es la ruta estándar que libva ya intenta probar; el RPM se puede instalar con `sudo dnf install mesa-va-drivers-freeworld` (versión sincronizada con mesa del sistema, exigida por el empaquetado).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-003 | Maybe | Nota de infraestructura del build (qué backend se enlaza) | Updated al aplicar |
| DES-004 | Yes | Ruta de encoder hardware activa en Linux/AMD | Updated al aplicar |
| REQ-005 | No | Contrato de stream intacto | None |
| Code | Yes | Fallback `LIBVA_DRIVERS_PATH` freeworld en `CreateHardwareH264Encoder` (una única vez, con log) | Aplicado en `TwinpadClient.cpp` |
| Build/infra | Yes | Copia del driver freeworld a `bin/va-freeworld` (o instalación del RPM) | Aplicado localmente |
| Docs de testing | Yes | TEST-082 ya cubre el intento hardware y el fallback CPU; verificación física pendiente | Pendiente del usuario |

## 5. Options considered

1. Rebuild de ffmpeg con VAAPI (RPM Fusion o estático) y relink de Cemu — **seleccionada** por el owner; al ejecutarla resultó innecesaria (el ffmpeg de vcpkg ya tiene VAAPI; el faltante era el driver del sistema).
2. Bajar resolución para mantener OpenH264 CPU — rechazada por el owner.
3. Mantener CPU y cerrar solo el defecto de continuidad — rechazada (no resuelve el throughput).
4. (Nueva) Resolver el entrypoint de encode con el driver VA-API freeworld de RPM Fusion (instalación del RPM o copia local reconocida vía LIBVA_DRIVERS_PATH en el retry del código) — **aplicada**.

## 6. Decision

- **Outcome:** Decided (2026-10-29): implementar la opción 1.
- **Rationale:** es la única opción que alcanza el objetivo del video fluido (≥30 fps) sin cambiar el wire; la GPU del host soporta VAAPI; el código ya está preparado.
- **Rollout:** al aplicar, registrar en el changelog de SPEC-003/DES-004 y en `docs/engineering` el procedimiento de build (variable del sistema o script), y ejecutar la verificación física (fps y CPU/GPU con el título).

## 7. Execution checklist

- [x] Product owner approval recorded (2026-10-29)
- [x] Sonda reproducible: encode h264_vaapi OK contra el driver freeworld (2026-10-29)
- [x] Rebuild de la dependencia ffmpeg con VAAPI — no fue necesario (el estático de vcpkg ya lo trae); relink ya correcto (`-lva-drm -lvpl -lstdc++`)
- [x] Fallback de driver en `TwinpadClient.cpp` (retry con `LIBVA_DRIVERS_PATH`, log Force)
- [x] Copia local del driver a `integrations/cemu/bin/va-freeworld`
- [x] Documentar el procedimiento (instalar `mesa-va-drivers-freeworld` sincronizado con la versión de mesa del sistema, o mantener la copia local)
- [x] TEST-082 revisado (sin cambio de contrato; verificación física pendiente del usuario)
- [ ] Verificación física (fps de emisión y carga CPU/GPU): relanzar Cemu y confirmar `[TwinpadVideo] encoder=hardware backend=h264_vaapi` en el log

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-29 | Twinpad team (agente) | Opened y decidido por el owner; evidencia del probe VAAPI |
| 0.2.0 | 2026-10-29 | Twinpad team (agente) | Diagnóstico corregido (el faltante era el driver VA-API del sistema, no ffmpeg); aplicación del retry freeworld en código y copia local del driver; pendiente verificación física |
