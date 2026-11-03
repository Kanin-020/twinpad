# Change Request (CR) — CR-064 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-064 |
| Title | Drawer Android: eliminar el slider de bitrate y fijar el bitrate del cliente (10 000 kbps, sin control de usuario) |
| Type | `Behavior change` |
| Status | `Applied (2026-11-03, code-applied; actualización de SPEC-002 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner («Elimina el slider de bitrate en android») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-002` (§6 drawer, apartado de calidad/bitrato), `DES-002` (si referencia el slider) |
| Sections | §6 (drawer) |
| Current version → new version | `SPEC-002 1.12.x → pendiente` |

## 2. Requested change

**Current:**

```text
El drawer de ajustes (CR-001) expone un slider de bitrate de video
(0…29000 + offset = 1 000…30 000 kbps, R.id.bitrate_seek) persistido en
SharedPreferences (`video_bitrate_kbps`); el default de fábrica es
30 000 kbps (CR-031). El valor elegido viaja en el `video_config`
en caliente (CR-001/CR-030) y alimenta también la escalera CR-058
(§8.4: el drawer del usuario es la referencia; nunca degradar por
encima del bitrate configurado).
```

**Proposed:**

```text
El drawer ya no expone control de bitrate. El bitrate del cliente queda
FIJO en 10 000 kbps (DEFAULT_VIDEO_BITRATE_KBPS), sin persistencia
(KEY_VIDEO_BITRATE retirado del load/save). El wire no cambia:
`video_config` sigue llevando bitrate_kbps (CR-029/CR-030), y la
escalera CR-058 sigue respetando la referencia (ahora el valor fijo).
```

## 3. Motivación

Evidencia medida en el diagnóstico del 2026-11-03 (sesión real de streaming
`cemu` vía UDP §4.4): con 30 000 kbps el server emitió ~3 400 datagrams/s y
el lector de la app del teléfono solo consume ~2 300 datagrams/s → el búfer
del socket UDP del teléfono se desborda (~1 100 drops/s sostenidos), los
fragmentos perdidos impiden completar AUs (solo 1–5 AU/s sobreviven), el
decoder cae en FLUSH→STARTED continuo y el render queda a 0 fps.

Además, el `video_config` de la app en el `stream_start` sobreescribe
cualquier cap del server (30 000 kbps) — con el slider fuera y el valor
fijo, el pipeline queda bajo la capacidad medida del cliente (10 000 kbps
≈ 920 datagrams/s, margen >2×) en todas las instalaciones.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-002 | yes | §6 drawer: fila del slider deja de existir; default de calidad | update pendiente (owner) |
| SPEC-003 | no | — | — |
| ARCH | no | — | — |
| DES-002 | no | — | — |
| TEST-061 | yes | androidTest: el store ya no persiste bitrate | updated in-code |
| TEST-069/089 | yes | default 30 000 → 10 000 kbps | updated in-code |
| Code | yes | `drawer_settings.xml`, `strings.xml`, `SettingsDrawerController.kt`, `AndroidSettingsStore.kt`, tests | aplicado |
