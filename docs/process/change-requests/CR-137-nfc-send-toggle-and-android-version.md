# Change Request (CR) — CR-137 — Twinpad

| | | |
|---|---|---|
| ID | CR-137 |
| Title | Android 0.12.0 y ajuste «Activar envío NFC» en el drawer, debajo de la cámara |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-27 |
| Decided | 2026-11-27 |
| Decided by | Owner (petición directa) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-006`, `DES-002` |
| Sections | `SPEC-006 §4/§12`; `DES-002 §1/§12` |
| Current version → new version | `SPEC-006 1.32.0 → 1.33.0`; `DES-002 0.10.0 → 0.11.0` |

## 2. Requested change

**Current:**

```text
- La app Android declara versionName 0.10.0 (versionCode 1000).
- El lector NFC de Amiibo (CR-126) se activa solo con la Activity visible, la
  sesión `Linked` y el juego «cemu» seleccionado; no hay interruptor en el
  drawer para el envío.
```

**Proposed (petición del owner):**

```text
- `versionName = "0.12.0"` (versionCode 1200, major*10000 + minor*100 + patch).
- Nuevo interruptor «Activar envío NFC» en el drawer de ajustes, justo debajo
  de la sección «Cámara del teléfono».
- El interruptor persiste en `AndroidSettings.nfcSend` y gatea el lector
  `amiibo_tag`: con el ajuste apagado no se activa el reader mode ni se envía
  nada; si se apaga con un tag montado se envía `amiibo_tag present:false`
  (misma ruta de retirada de CR-126) y el tag se desmonta.
- Nace **apagado** (opt-in, decisión del owner del 2026-11-27): hasta que el
  usuario marque «Activar envío NFC» no se activa el lector ni se envía
  `amiibo_tag`; con el ajuste apagado el Amiibo de CR-126 queda inactivo.
```

## 3. Motivation

El owner pide dos cosas en la misma petición: subir la versión visible de la app
a 0.12.0 y poder activar o desactivar el envío NFC desde las opciones, en el
hueco justo debajo de la cámara del teléfono. El único envío NFC de la app es el
relay `amiibo_tag` (CR-126), así que el ajuste gobierna ese lector.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | no | El AC-7 sigue vigente; el ajuste es una preferencia del cliente | none |
| SPEC-006 | yes | §4: la activación del lector añade el ajuste «Activar envío NFC» | §4/§12 + changelog 1.33.0 |
| ARCH | no | Sin componentes ni mensajes nuevos | none |
| DES-002 | yes | Nuevo `AndroidSettings.nfcSend` y su interruptor | §1/§12 + changelog 0.11.0 |
| TEST-069 (extendido) | yes | Defaults estables: `nfcSend` nace activado y no altera `video_config` | assertions añadidas |
| TEST-0222 | no | El lector NFC sigue cubierto por CR-126 | none |
| Code | yes | `build.gradle.kts`, `AndroidSettingsStore`, `SettingsDrawerController`, `PairingActivity`, `AmiiboNfcController`, `drawer_settings.xml`, `strings.xml` | aplicado |
| Data / migrations | no | Preferencia nueva con default `true`; sin migración | none |
| Integrations / contracts | no | `amiibo_tag` no cambia | none |

## 5. Options considered

1. Interruptor que gatea el lector NFC, **apagado por defecto (opt-in)** —
   **elegida** por el owner: «activar mandar NFC» se marca expresamente.
2. Interruptor activado por defecto — descartada tras la petición del owner:
   el envío NFC debe activarlo el usuario.
3. Interruptor solo persistido, sin efecto — descartada: «activar mandar NFC»
   debe poder apagar el envío real.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** petición directa del owner.
- **Rollout:** cambio local de la app; el wire `amiibo_tag` y el servidor no
  cambian. Versión visible 0.12.0 (versionCode 1200).

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-006 1.33.0, DES-002 0.11.0)
- [x] Dependent documents updated (DES-002 §1/§12, SPEC-006 §4/§12)
- [x] Tests extended (TEST-069: default activado y ajuste local)
- [x] `testing/traceability.md` rows updated (historial + fila REQ-006)
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por petición del owner |
| 0.2.0 | 2026-11-27 | Buffy (agente) | «Activar envío NFC» pasa a nacer apagado (opt-in), por petición del owner |
