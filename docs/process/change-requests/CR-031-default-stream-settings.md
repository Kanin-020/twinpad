# Change Request (CR) — CR-031 — Twinpad

| | |
|---|---|
| ID | CR-031 |
| Title | Ajustes por defecto: 1080p, escalado contener, bitrate máximo, HUD fps y botones visibles |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-28 |
| Decided | 2026-10-28 |
| Decided by | Product owner (petición expresa en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §11, `testing/traceability.md` |
| Sections | Ajustes del drawer: defaults de fábrica |
| Current version → new version | SPEC-002 `1.8.0 → 1.9.0` |

## 2. Requested change

**Current:**

```text
Los ajustes del drawer parten de: resolución 1280×720 (1920×1080 solo
aparece como fallback de valor persistido desconocido), escalado
"expand", bitrate 8.000 kbps. El HUD de fps ("Mostrar fps") y los
controles táctiles ("Mostrar botones") ya están activados por defecto
(true en código), pero el SPEC no lo deja explícito.
```

**Proposed:**

```text
Defaults de fábrica (instalación sin preferencias previas):
- Resolución: 1920×1080 (R17; CR-030 conserva la nativa del DRC si la
  nativa es menor, por lo que en la práctica el DRC emite su nativa).
- Escalado de pantalla: "contain"/contener (R12). Los usuarios que ya
  persistieron una preferencia la conservan.
- Bitrate objetivo: 30.000 kbps (máximo del rango 1.000–30.000, R15).
- "Mostrar fps" (HUD) y "Mostrar botones" (controles táctiles):
  activados por defecto, ahora explícito en el SPEC (R18). Sin cambio
  de código: el código ya los arranca en true.
El wire, el rango del slider y la persistencia no cambian.
```

## 3. Motivation

El owner quiere que una instalación nueva arranque lista para la mejor
calidad: DRC a 1080p, pantalla completa sin deformar (contener) y el
bitrate al máximo tras el fix de CR-027 (rate-control sobre la cadencia
real). Mostrar fps y botones encendidos de fábrica reduce el setup.

## 4. Impact analysis

- **App:** `AndroidSettingsStore` (constantes default: `1920×1080`,
  `30_000`, `scaleMode = CONTAIN` en data class; `load()` solo devuelve
  EXPAND si quedó persistido) y fallback de resolución desconocida del
  drawer marca 1920×1080. TEST-069 aserta los valores concretos.
- **Server/Cemu:** sin cambios. `video_config` solo cambia de valor por
  sesión nueva de instalaciones nuevas.
- **Compatibilidad:** preferencias previas no se reescriben; un usuario
  con 8.000 kbps persistido sigue en 8.000 hasta cambiarlo.
- **Riesgo:** ninguno conocido; el encoder de Cemu ya acepta 30.000 con
  clamp idéntico (CR-027/TEST-068).

## 5. Verification

| Gate | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | `AndroidSettingsStoreTest.stream_settings_have_stable_defaults` (TEST-069) verde con los nuevos defaults |
| ktlint/build | `ktlintMainSourceSetFormat` + `assembleDebug` sin errores |
| Físico | Eh de banco: primer arranque de la app muestra 1920×1080, Contener, slider al máximo, fps y botones visibles |

## 6. Records

- `_index.md`: CR-031 en Applied; próximo ID libre pasa a CR-032.
- SPEC-002 1.9.0: R12 (default contain), R15 (default 30.000),
  R17 (default 1920×1080), R18 (fps y botones visibles por defecto),
  Changelog.
- Traceability: TEST-069 con la nueva cobertura de defaults.
