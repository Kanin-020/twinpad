# Change Request — CR-050 — Diagnóstico de colores del DRC por juego (Wind Waker vs Nintendo Land)

| | |
|---|---|
| ID | CR-050 |
| Title | Colores incorrectos del stream DRC en títulos que pasan por la normalización GPU (CR-040) |
| Type | `Defect fix` |
| Status | `Applied` (instrumentación + fix de gamma del blit; verificación física del owner pendiente) |
| Author | Twinpad team (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Product owner (conversación; petición directa) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 |
| Sections | DES-004 ruta de captura del DRC: readback directo vs normalización GPU (CR-040) |
| Current version → new version | DES-004 1.13.0 → 1.14.0 |

## 2. Requested change

**Current (al abrir el CR):**

```text
El owner reporta colores del stream DRC correctos en Nintendo Land pero
incorrectos en juegos como Wind Waker HD. Nintendo Land alimenta el
FrameLoop vía copia directa del readback (BGRA8 del DRC; CR-036), Wind
Waker HD vía la normalización en GPU con vkCmdBlitImage (CR-040, DRC
A2B10G10R10 packed, bpp=0) cuyo destino elige UNORM/SRGB con
LatteGPUState.drcBufferUsesSRGB. Es el único eslabón por juego de la
cadena de color; sin evidencia de los bytes reales no se puede elegir
entre canal intercambiado, doble/gamma faltante o alineación de fila.
```

**Applied (2026-10-31):**

```text
Instrumentación fail-loud en el FrameLoop (CR-050): cada cambio de formato
del gamepad se loguea con fmt/w/h (crítica: la línea de CR-040 solo se
emite una vez por sesión y esconde qué camino tomó el segundo juego).
Además, con la variable de entorno TWINPAD_CAPTURE_DUMP_DIR apuntando a un
directorio existente, el FrameLoop vuelca el primer frame RGB convertido
(PPM P6) por cada combinación (formato, w, h), hasta 6 dumps por sesión,
con log del nombre de archivo. Sin cambio de wire ni de bytes de la ruta
de producción: los dumps son a la misma media que va al encoder.
El fix de color definitiva entra en CR-050 Append cuando el dump diga si
el defecto es canal (R/B), gamma (washed/dark) o pitch (franja/desalineo).
```

## 3. Motivation

El owner compara Nintendo Land (colores correctos) con Wind Waker (incorrectos)
y recuerda que el asunto está "relacionado con el tamaño". El mapa estático
no distingue entre las tres hipótesis del blit de CR-040: (1) orden de canales
del packed A2B10G10R10, (2) elección de destino UNORM/SRGB del blit vs la
semántica de la superficie fuente (doble encode sRGB), (3) realineación de
pitch/pixel row del surface. Las tres producen "colores malos" con síntomas
distintos y el fix correcto depende de cuál ocurre; el repo resuelve esta
clase de defectos de Cemu con medición (CR-036, CR-040) antes de cambiar la
ruta de producción.

## 4. Impact analysis

| Área | Documents to update | Implements | Version change |
|---|---|---|---|
| Cemu plugin | DES-004 | diag log + dump PPM opcional | 1.14.0 |

- Wire: sin cambio (los bytes hacia el encoder/server/teléfono son idénticos).
- SPEC-003/006: intactas.
- Riesgos: ninguno telemétrico (≤6 I/O por sesión con env var no definida → 0 I/O).

## 5. Implementation notes

- `TwinpadClient.cpp` (FrameLoop): helper local `TwinpadFormatName()`;
  log por cambio de formato con w/h; dump PPM del RGB convertido con
  `TwinpadGetEnv("TWINPAD_CAPTURE_DUMP_DIR")` (una vez por clave
  formato+w+h, máx 6, log de éxito/fracaso truncado).
- v2 (aplicada tras el primer reporte del owner): además del log de
  cambio de formato, en cada fusión de formato el FrameLoop loguea
  estadísticas del RGB convertido (avg/min/max por canal) y 3 píxeles
  de la fila media (`(w/4,h/2)`, `(w/2,h/2)`, `(3w/4,h/2)`), de modo que
  el diagnóstico no depende de abrir el PPM. Dump PPM se mantiene.
- Encabezado `cstdlib` ya presente (getenv); añade `<fstream>`,
  `<unordered_set>`, `<fmt/format.h>`.

## 6. Verification / decision log

| | |
|---|---|

→ Gate Cemu: `ninja Cemu_release` en verde. Verificación física pendiente:
una sesión de Wind Waker HD (y una de Nintendo Land si cabe comparación)
con `TWINPAD_CAPTURE_DUMP_DIR` definida; el owner comparte el nombre de
los archivos y lo que se ve en ellos; el fix de color se anexa a CR-050.

→ Segunda pasada (2026-10-31, tras el reporte del owner): con la sesión de
Wind Waker el owner confirma «oscuro, colores más intensos y apagados,
sombras acentuadas» — la firma de un decode sRGB de más. La causa: el blit
de CR-040 elegía destino `R8G8B8A8_SRGB` cuando `drcBufferUsesSRGB` está
activo, y el encode implícito del blit en radeonsi no ocurre (el mismo
driver ya había roto el contrato estricto en CR-041); el FrameLoop então
decodificaba bytes ya lineales. Fix aplicado en `CaptureTwinpadFrameAsync`:
 destino del blit SIEMPRE `R8G8B8A8_UNORM` (transferencia raw sin conversiones implícitas
 y determinista en cualquier driver) + la etiqueta sRGB del slot pasa a
 ser una decisión de semántica GX2 (`slotSemanticFormat` separada del
 formato real del destino). El FrameLoop entonces decodifica exactamente
 una vez, igual que la ruta directa de Nintendo Land.

→ Tercera pasada (2026-10-31, síntoma persiste + referencias visuales del
owner en `Images/`): el owner aporta capturas del teléfono (oscuro/saturado)
y de la ventana DRC de Cemu (correcta/brillante) del mismo momento de Wind
Waker. El análisis numérico de regiones equivalentes descarta una simple
gamma por canal (los per-canal no encajan en potencia única) y sitúa el
defecto en la cadena etiqueta→FrameLoop, no en el contenido del blit:
el blit UNORM→UNORM es raw y los bytes que copia son los mismos que la
ventana DRC de Cemu pinta correctos (display-referidos). La firma "oscuro
y saturado" solo vuelve a ocurrir si el FrameLoop decodifica una vez de
más — y la segunda pasada dejó exactamente ese decode: el
`slotSemanticFormat` etiquetaba el slot desde la semántica del ORIGEN
(`drcBufferUsesSRGB`), no del destino, así que Wind Waker (DRC GX2 con bit
sRGB) entraba con etiqueta `Rgba8Srgb` y el FrameLoop linealizaba los
bytes raw display-referidos. Nintendo Land no lo sufria: su ruta directa
copiaba el surface tal cual y su slot llegaba sin etiqueta sRGB. Fix (v3)
 aplicado en `CaptureTwinpadFrameAsync`: la etiqueta del slot es la del
 FORMATO DEL DESTINO (`slotFormat = convertedFormat`, `R8G8B8A8_UNORM`
siempre en el camino normalizador) — mismo contrato que la captura de
screenshots upstream (`CaptureFrameTexture`, blit+etiqueta del destino)
y que la ruta directa de Nintendo Land: el FrameLoop toca los bytes
cero veces. `slotSemanticFormat` eliminado. Síntoma esperado: color
idéntico a la ventana DRC de Cemu en Wind Waker sin cambiar nada en
Nintendo Land.

→ Cuarta pasada (2026-10-31, mejora parcial del owner «se ve mejor pero
todavía no como debería» + captura `Images/Android 2.jpg`): el análisis
numérico sobre la madera del muelle cierra el diagnóstico con una
coincidencia casi exacta (Δ≤6/255): madera en Android 2 =
srgb_decode(madera en la referencia de Cemu). El wire sigue llegando
linealizado una vez, no menos: la hipótesis de la segunda/tercera pasada
(bytes de superficie display-referidos) era incorrecta. La superficie
correcta es: el DRC sRGB GX2 de Wind Waker es una superficie packed
10-bit sin variante Vulkan sRGB, por lo que el attachment UNORM de
Cemu NO aplica el encode de escritura y la superficie queda LINEAL
porque el juego escribe lineal esperando el encode de la GPU igual
que haría el hardware real con ese surface format. La ventana DRC de
Cemu compensa en su presentación; la captura no debe supone bytes
display-referidos. La aplicación del encode correcto: el
blit destino `R8G8B8A8_SRGB` aplica el encode al escribir (el driver lo
respeta — la captura upstream de screenshots, `CaptureFrameTexture`, usa
exactamente este patrón y produce capturas correctas en radeonsi). Los
cuatro estados por los que pasó el defecto quedan explicados por una
sola pieza wire = decode_count(encode_count, tag):
  CR-040 orig.: encode(blit srgb) + decode(tag srgb) → wire lineal → oscuro.
  v2 (CR-050):   sin encode(blit unorm) + decode(tag srgb) → doble lineal → oscuro.
  v3 (CR-050):   sin encode(blit unorm) + sin decode(tag unorm) → lineal → oscuro suave («mejor»).
  v4 (este fix): encode(blit srgb) + sin decode(tag unorm) → display-coded → CORRECTO
(Nota: Nintendo Land siempre display-coded sin tocar nada: su superficie 8-bit
sRGB SÍ codifica en el store de Vulkan). Fix v4 en `CaptureTwinpadFrameAsync`:
destino del blit `R8G8B8A8_SRGB` cuando `drcBufferUsesSRGB` (con la
verificación BLIT_DST correspondiente) y etiqueta del slot SIEMPRE
`R8G8B8A8_UNORM` (`slotTagFormat`). El log de normalización ahora
indica el destino aplicado. Síntoma esperado: color idéntico a la
ventana DRC de Cemu en Wind Waker.

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Twinpad team (agente) | Instrumentación aplicada; fix de color pendiente del dump |
| 0.2.0 | 2026-10-31 | Twinpad team (agente) | Fix de gamma del blit (destino unorm + etiqueta sRGB desde GX2); stats/samples en el log de cambio de formato |
| 0.3.0 | 2026-10-31 | Twinpad team (agente) | Etiqueta del slot desde el formato destino (raw passthrough); elimina decode residual del FrameLoop en el camino normalizador |
| 0.4.0 | 2026-10-31 | Twinpad team (agente) | Encode sRGB dentro del blit (destino SRGB con drcBufferUsesSRGB, patrón CaptureFrameTexture) + etiqueta slot siempre unorm; calibrado con la medición de madera (a2 ≈ srgb_decode(ref)) confirmada por el owner: superficie 10-bit sRGB GX2 deja bytes lineales en Cemu |
