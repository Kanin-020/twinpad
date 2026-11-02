# REPORT.md — Refactor del módulo Android (sin cambio funcional)

Fecha: 2026-11-02 · Alcance: `android/` (módulos `:app` y `:hid`) · Commits: —

## Objetivo

Revisión integra del código Android aplicando, **en este orden**: (1) limpieza y
convenciones de código limpio, (2) SOLID/DRY, (3) arquitectura, (4) bajo
acoplamiento y eliminación de duplicados, (5) documentación de funciones y
eliminación de código innecesario.

**Restricción respetada:** ningún cambio de comportamiento. El contrato del
wire (SPEC-001/003/004/005), los CRs implementados y las entradas/salidas de
cada función son idénticos a los previos; todos los gates quedan verdes.

## 1. Limpieza: nombres y documentación

- **KDoc completo en funciones públicas y clases** de los paquetes tocados,
  siguiendo las convenciones del repo (referencias a SPEC/CR/§ y reglas de
  negocio, en el idioma del propio archivo cuando ya lo usaba):
  - `stream/`: `StreamStats`, `TileComposer`, `TileCompositor`,
    `FrameRenderer`, `VideoTrace`, `H264MediaClient`, `UdpMediaClient`.
  - `audio/`: `AudioPlayback` (incluye KDoc a nivel superior de
    `AndroidPlaybackTrackFactory` y `AndroidPlaybackTrack`).
  - `wire/`: `CameraFrameCodec`, `MicChunkCodec`, `GameListCodec`,
    `HandshakeCrypto`, `MediaCrypto`, y el nuevo `CryptoPrimitives`.
  - `pairing/`: `MediaHandshakeContext`, `PairingMediaDependencies`,
    `PairingSelectionState`, `RumblePlayer`, `PairingMediaSession`.
- **Nombres explicativos** (sin cambiar identificadores de dominio del
  glosario): parámetros crípticos de validación en `ControlProfile` hechos
  explícitos; variables intermedias de `validateCoords` renombradas a
  `insideHorizontal`/`insideVertical` para reflejar cada condición.
- **Typos en comentarios corregidos** (p. ej. «kodek» → codec) o comentarios
  engañosos reescritos donde describían mal el flujo real.

## 2. SOLID y DRY

- **DRY — criptografía del wire sin duplicados.** Nuevo archivo
  `wire/CryptoPrimitives.kt`: las primitivas HMAC-SHA256 y AES-256-GCM vivían
  duplicadas en `HandshakeCrypto`, `MediaCrypto` y el codec UDP de stream.
  Ahora las tres facetas delegan en un único objeto con paridad documentada
  con `core::crypto`. Los bytes producidos son idénticos (verificado por los
  tests del wire existentes).
- **Responsabilidad única (SRP).** Helpers puros de decisión se movieron a
  unidad propia documentada en el nivel que les corresponde:
  - En `PairingMediaSession`, `isNewFrame`/`isFrameDiscontinuous` son ahora
    funciones de nivel archivo con parámetros explícitos (además corrige el
    hallazgo detekt `TooManyFunctions`, umbral 22).
  - En `TileComposer`, `isNewFrame` quedó como función documentada con forma
    de bloque; semántica idéntica (fallback `frameNumber == 0L` cuando no hay
    base, y luego `fn` estrictamente creciente).

## 3. Arquitectura

Barrido de dependencias entre paquetes (`core`, `wire`, `stream`, `audio`,
`surface`, `hid`, `pairing`, `ui`):

- `core` no importa Android ni red (testeable en JVM) — preservado.
- El módulo `:hid` no importa nada de `:app` — preservado.
- `wire` solo consume `core` (errores y parser QR); `stream`, `audio`,
  `surface` y `hid` consumen únicamente `wire`/técnica propia — capas limpias.
- `pairing` depende de `stream`, `audio`, `hid`, `wire`, `core` como
  orquestador — esperado.

**Hallazgo registrado (sin acción en este refactor):** `PairingViewModel`
importa `ui.AndroidSettingsStore`, es decir, una capa intermedia depende de la
capa de vista (inversión de dependencias asimétrica). Mover
`AndroidSettings`/`AndroidSettingsStore` a un paquete `settings` propio es la
mejora correcta, pero `VideoScaleMode` (su compañera semántica) vive en `ui` y
el giro completo toca 6+ archivos y tests; se deja documentado como mejora
futu­ra para no introducir riesgo funcional en esta pasada.

## 4. Duplicados y código innecesario eliminados

- `ui/ProfileTouchController.kt`:
  - Se eliminó `selectControl(index)`: función pública sin ninguna llamada en
    producción ni tests (la selección se fija internamente en
    `onPointerDown`); su estado ya se expone vía `selectedControlIndex`.
  - Se eliminó la asignación duplicada `draggingIndex = -1` al final de
    `cancelPointers` (ya se hace tras `clear()`).
- Duplicados de primitivas criptográficas consolidados en
  `wire/CryptoPrimitives.kt` (ver §2).
- Verificación de código muerto por barrido: toda función declarada (excluyendo
  overrides de plataforma Android como `on*`, `surface*`, `TextWatcher`) tiene
  referencias reales fuera de su declaración — salvo el caso eliminado arriba.

## 5. Ajustes de estilado exigidos por los gates

Los gates detectaron hallazgos estáticos y se corrigieron con cambios de
forma, no de semántica:

- `ControlProfile.kt`: tres líneas largas divididas (`validateHid` y
  `validateCoords`); el mensaje de error de coordenadas se partió en
  concatenación de literales → **texto idéntico en runtime**.
- `PairingMediaSession.kt`: helpers de nivel archivo (ver §2) para el umbral
  `TooManyFunctions` de detekt.
- `TileComposer.kt`: firma de `isNewFrame` con cuerpo de bloque (ktlint).
- `AudioPlayback.kt`: indentación de KDoc a nivel superior corregida (ktlint).

## 6. Gates ejecutados (todos verdes)

| Gate | Comando | Resultado |
|---|---|---|
| Compilación | `:app:compileDebugKotlin`, `:hid:compileDebugKotlin` | OK |
| ktlint | `:app:ktlintCheck`, `:hid:ktlintCheck` | OK |
| detekt | `:app:detekt`, `:hid:detekt` | OK (0 issues ponderados) |
| Tests unitarios | `:app:testDebugUnitTest`, `:hid:testDebugUnitTest` | OK |

Rust y server no se tocaron en esta pasada.

## 7. Cambios archivo por archivo

- `android/app/src/main/kotlin/.../audio/AudioPlayback.kt` — KDoc y corrección
  de indentación de KDoc top-level.
- `.../pairing/MediaHandshakeContext.kt` — documentación y limpieza menor.
- `.../pairing/PairingMediaDependencies.kt` — documentación.
- `.../pairing/PairingMediaSession.kt` — helpers `isNewFrame`/`isFrameDiscontinuous`
  a nivel archivo, documentación de ciclo de vida, corrección detekt.
- `.../pairing/PairingSelectionState.kt` — documentación.
- `.../pairing/RumblePlayer.kt` — documentación.
- `.../stream/FrameRenderer.kt`, `TileCompositor.kt`, `VideoTrace.kt` — KDoc.
- `.../stream/StreamStats.kt` — KDoc de funciones públicas y corrección de typos.
- `.../stream/TileComposer.kt` — KDoc + `isNewFrame` en forma de bloque.
- `.../stream/H264MediaClient.kt` — documentación de AU y flujo del cliente.
- `.../stream/UdpMediaClient.kt` — documentación y delegación en
  `CryptoPrimitives` (elimina duplicados AES-GCM).
- `.../surface/ControlProfile.kt` — nombres explícitos, líneas largas divididas,
  KDoc.
- `.../ui/ProfileTouchController.kt` — eliminación de `selectControl` y de la
  asignación duplicada de `draggingIndex`.
- `.../wire/CameraFrameCodec.kt`, `MicChunkCodec.kt`, `GameListCodec.kt` — KDoc.
- `.../wire/HandshakeCrypto.kt`, `MediaCrypto.kt` — delegación en
  `CryptoPrimitives` (DRY) y documentación.
- **Nuevo:** `.../wire/CryptoPrimitives.kt` — primitivas compartidas HMAC / AES-GCM.
- `REPORT.md` — este documento.

## 8. Riesgo y garantía de no-regresión

- Ningún identificador del dominio renombrado; ningún mensaje del wire alterado.
- La consolidación criptográfica quedó cubierta por los tests existentes del
  módulo (`HandshakeCodecTest`, `UdpMediaClientTest`, tests de `:hid`, entre
  otros) y toda la suite de `:app` + `:hid` ejecutó verde.
- El código muerto eliminado no tenía llamadas (verificado por barrido grep
  sobre `main` + tests de ambos directorios).
