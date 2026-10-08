# SPEC-006 — Detección de juego con SDK Twinpad (modelo B)

| | |
|---|---|
| ID | SPEC-006 |
| Title | Detección de juego con SDK Twinpad (REQ-006) |
| Version | 1.0.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-006 (AC-1; contratos de AC-2/AC-3 quedan en el SDK) |
| Realized by | DES-XXX (`-` hasta asignar) |
| Verified by | TEST-021 |
| Last updated | 2026-10-07 |

## 1. Purpose

Define cómo el Servidor PC detecta automáticamente que un Juego / Aplicación huesped con SDK Twinpad integrado está en ejecución (REQ-006 AC-1) y qué estado observable produce esa detección: el juego aparece como "compatible disponible" para la app y sus streams pueden activarse.

## 2. Definitions

- **Juego compatible**: juego con SDK Twinpad integrado en ejecución en el PC Host.
- **Anuncio**: identificación que el juego entrega al Servidor al arrancar (nombre, id de juego, capacidades).
- Ver CTX §8: Plugin, SDK Twinpad, Servidor PC.

## 3. Preconditions

- Servidor Twinpad activo (REQ-001).
- Un juego con SDK integrado arranca en el mismo PC (mismo host, sin detección por red).

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `game_id` | string | 1–64 chars, `[a-z0-9_.-]`, único por juego | yes |
| `game_name` | string | 1–128 chars legibles | yes |
| `capabilities` | conjunto | subconjunto de `second_screen`, `audio`, `input_consumption` | yes |

Canal: mecanismo local del PC (canal IPC del SDK ↔ Servidor); el framing es decisión de DES-006.

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Juego detectado | aparece como "juego compatible disponible" en el estado del server (visible por logs/UI y por la app) | anuncio válido mientras el proceso viva |
| Juego detectado publicado a la app | mensaje de sesión que lista el juego disponible | sesión autenticada activa |
| Juego desaparecido | deja de estar disponible al terminar su proceso (crash incluido) | el server verifica liveness |

## 6. States

- `D0 NoGame`: ningún juego compatible en ejecución.
- `D1 Available`: ≥ 1 juego compatible detectado y vivo.

| From | Event | To | Side effects |
|---|---|---|---|
| `D0` | anuncio válido | `D1` | el juego se lista como disponible |
| `D1` | el proceso del juego termina (cualquier causa) | `D0` si era el único | deja de estar disponible; streams que dependían de él se detienen con `stream_stop`/`audio_stop` (SPEC-003/005) |

Invariant: solo juegos con anuncio vivo figuran como disponibles; un crash nunca deja un "fantasma".

## 7. Main flow

1. El juego con SDK arranca y envía su anuncio (`game_id`, `game_name`, `capabilities`) al Servidor.
2. El Servidor valida el anuncio (§4) y lo registra como disponible.
3. Con sesión autenticada, el Servidor publica la lista de juegos disponibles a la app.
4. La app muestra el juego como compatible disponible (REQ-006 AC-1).

## 8. Alternative flows

### 8.1 Dos juegos con SDK simultáneos

- **Trigger:** un segundo juego se anuncia mientras el primero vive.
- **Flow:** ambos quedan listados; la app/user elige con cuál operar; los streams de SPEC-003/005 emiten del juego elegido.
- **Result:** cada anuncio es independiente; no hay conflicto de `game_id` (id distinto por juego).

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| Anuncio con campos fuera de constraints | anuncio rechazado; el juego no aparece disponible | sin código de sesión (error local del SDK) |
| `game_id` duplicado de un juego ya vivo | el segundo anuncio se rechaza | error local del SDK |
| El juego crashea | deja de estar disponible; la sesión (pairing) no se rompe; los streams envían `*_stop` | sin error al usuario |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Anuncio con capacidades vacías | el juego figura como compatible pero sin streams; la app muestra "sin segunda pantalla activa" |
| El server se reinicia con el juego vivo | el juego re-anuncia (el SDK reintenta); mientras no haya anuncio, no disponible |
| Capacidad declarada que luego no se usa | sin efecto: las capacidades son declarativas |

## 11. Business rules

- **R1:** la detección es automática: ningún paso del usuario habilita un juego compatible (REQ-006 AC-1).
- **R2:** la disponibilidad exige anuncio vivo (liveness verificado por el server).
- **R3:** el crash de un juego jamás rompe la sesión de SPEC-001 (REQ-006 §8).

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Campos del anuncio | este documento §4 | v1 |
| Canal IPC SDK ↔ Servidor | DES-006 (por asignar) | local al PC |
| Streams que el juego alimenta | SPEC-003 (video), SPEC-005 (audio) | referenciados |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Detección automática (REQ-006 AC-1)
  Given el servidor activo y ningún juego vivo
  When un juego con SDK arranca y anuncia game_id="pacdemo" con capabilities=[second_screen,audio]
  Then el juego figura como compatible disponible y la app lo muestra

Scenario: Crash no rompe la sesión (REQ-006 §8)
  Given "pacdemo" disponible y streams de video/audio activos
  When el proceso del juego crashea
  Then "pacdemo" deja de estar disponible, los streams envían *_stop
  And la sesión de pairing y la app siguen operativas
```

## 14. Invariants

- La lista de juegos disponibles coincide exactamente con los anuncios vivos.
- Ningún stream de SPEC-003/005 emite contenido de un juego no disponible.

## 15. What "complies" means

Cumple este SPEC quien honre §3, produzca §5, reproduzca §9 con sus triggers, cumpla §10, mantenga §14 y pase §16. Lo no definido no es requerido.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Detección automática (AC-1) y liveness | TEST-021 |

## 17. Open questions

- ¿Lenguajes/runtimes soportados por el SDK en v1 (C/C++ nativo, otros)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): SDK v1 como crate Rust (`twinpad-sdk`) con API de anuncio; el wrapper C/C++ (motores/AAA) entra como capa FFI posterior.**
- ¿Mecanismo IPC concreto (named pipe, localhost socket)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): TCP localhost (127.0.0.1:8421 por defecto) con NDJSON, mismo estilo de framing que la sesión de SPEC-001; ver ADR-008. La conexión viva ES el anuncio vivo (liveness R2/§9); named pipes de Windows quedan como alternativa futura.**

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: detección de juego con SDK |
| 1.0.0 | 2026-10-08 | Owner (usuario) | Approved: IPC por TCP localhost NDJSON (ADR-008); SDK v1 en Rust; publicación a la app incluida (AC-1 completo) |
