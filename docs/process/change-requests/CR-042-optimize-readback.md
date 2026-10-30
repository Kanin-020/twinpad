# CR-042 - Optimizar pipeline de readback Twinpad

## Descripción
Optimizar el pipeline de readback del DRC para reducir el tiempo `readback_ready` de ~43 ms a <25 ms (objetivo fps ≥25) mediante tres optimizaciones principales:

1. Reducir resolución de readback de 4K (3840×2160) a 1080p (1920×1080).
2. Evitar `vkCmdBlitImage` con filter NEAREST cuando no es necesario; usar `vkCmdCopyImageToBuffer` directo.
3. Cambiar memoria de readback a `VK_MEMORY_PROPERTY_HOST_CACHED_BIT` para reducir latency de coherencia.

## Motivo
El log actual muestra `readback_ready avg_us≈43 ms` (~23 fps teórico) y picos de 74 ms (~13 fps). El cuello es la transferencia de 3840×2160×4 bytes = 16.7 MB por frame desde la GPU a la CPU. Reducir a 1080p (6.2 MB) y eliminar la blit innecesaria debería bajar el tiempo a <25 ms.

## Superficie afectada
- `integrations/cemu/src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.cpp`: función `CaptureTwinpadFrameAsync` y `EnsureTwinpadDrcConvertImage`.
- `integrations/cemu/src/Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h`: constantes `TWINPAD_READBACK_SIZE`, `TWINPAD_READBACK_SLOT_COUNT`.
- `docs/testing/traceability.md`: TEST-073/082 actualizados si es necesario.

## Cambios propuestos

### 1. Reducir resolución de readback (VulkanRenderer.h)
Cambiar la constante `TWINPAD_READBACK_SIZE` de:
```cpp
static const inline uint32 TWINPAD_READBACK_SIZE = 3840 * 2160 * 4; // CR-036: caber un DRC hasta 4K RGBA/BGRA
```
a:
```cpp
static const inline uint32 TWINPAD_READBACK_SIZE = 1920 * 1080 * 4; // CR-042: readback a 1080p para reducir ancho de banda
```

### 2. Optimizar `CaptureTwinpadFrameAsync` (VulkanRenderer.cpp)
En la sección donde se copia la imagen al buffer (`newestCompleted.data`), hacer lo siguiente:

**A) Evitar `vkCmdBlitImage`**: Cuando el layout de imagen de origen ya es `VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL` y el formato es compatible (R8G8B8A8_UNORM/SRGB, B8G8R8A8_UNORM/SRGB), saltarse el blit y hacer `vkCmdCopyImageToBuffer` directo.

**B) Cambiar flags de memoria**: En la creación de `m_twinpadDrcConvertImage` (o la imagen de readback), añadir `VK_MEMORY_PROPERTY_HOST_CACHED_BIT` para reducir la latency de coherencia. Si el driver no lo soporta, fallback a `HOST_VISIBLE | HOST_COHERENT`.

**C) Reducir número de slots**: De `TWINPAD_READBACK_SLOT_COUNT = 3` a `2` (ahorro de memoria sin sacrificar funcionalidad con el esquema de ring actual).

### 3. Ajustar dimensiones
También reducir la altura máxima en `EnsureTwinpadDrcConvertImage` de 2160 a 1080, o crear la imagen dinámicamente con la resolución actual de la ventana DRC.

## Medición
- **Before**: `readback_ready avg_us≈43 ms` (window de 5 s, 13 readbacks).
- **After**: objetivo `readback_ready avg_us<25 ms` con margen para encode + network.
- Verificar que `fps ≥25` en dispositivo físico después de los cambios.

## Versionado y rastreo
- No afecta versión de SPEC/DES (cambio interno de pipeline).
- TEST-073 y TEST-082 ya apuntan al nuevo contrato CR-041; este CR optimiza el tiempo sin cambiar el contrato.