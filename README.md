# Twinpad

Segunda pantalla + gamepad HID para PC desde un celular Android, vinculados por QR en la red local. Stack: Kotlin (Android) + Rust (PC/núcleo) — ver [ADR-001](docs/decisions/ADR-001-kotlin-rust-stack.md).

> Proyecto Spec-Driven: el comportamiento vive en `docs/specs/` y nada se implementa sin SPEC `Approved` ([workflow](docs/process/workflow.md)). Estado actual: **funcionando de punta a punta en banco físico** — pairing por QR, Segunda pantalla con duplicado del escritorio PC y controles táctiles (D-pad + A/B/X/Y) enviando inputs al server. Qué sigue: [`TODO.md`](TODO.md).

## Requisitos

| Herramienta | Versión |
|---|---|
| Rust + cargo | stable ≥ 1.85 |
| JDK | 21 (Temurin) |
| Android SDK | platforms 34/35 + build-tools; `ANDROID_HOME` apuntando al SDK |
| Gradle | no se instala: se usa el wrapper (`android/gradlew`, descarga 8.10.2 al primer uso) |

## Compilar y verificar (gates)

### Rust — workspace en la raíz

```bash
cargo fmt --all -- --check                            # formato (gate)
cargo clippy --workspace --all-targets -- -D warnings # lints (gate)
cargo build --workspace                               # compilar
cargo test --workspace                                # tests unitarios (gate)
```

### Android — desde `android/`

```bash
./gradlew ktlintCheck detekt   # lints Kotlin (gate)
./gradlew testDebugUnitTest    # tests unitarios (gate)
./gradlew assembleDebug        # APK debug: android/app/build/outputs/apk/debug/app-debug.apk
```

El CI (`.github/workflows/ci.yml`) ejecuta estos mismos gates en cada push/PR ([workflow §8](docs/process/workflow.md)).

## Correr

Aún no hay servidor ni app con comportamiento: el scaffold compila y pasa gates, pero la implementación arranca con la aprobación de [SPEC-001](docs/specs/SPEC-001-pairing-session.md). La matriz de qué prueba qué está en [`docs/testing/traceability.md`](docs/testing/traceability.md).

## Estructura

```text
core/               crate Rust: protocolo de sesión y tipos (sin I/O)
server/             Servidor PC + Twinpad Core (capture/, config-loader/)
sdk/                SDK Twinpad para juegos (REQ-006)
android/app|hid/    app Android y Motor HID (Kotlin)
docs/               documentación SDD (orden de lectura: docs/README.md)
```

Para agentes de IA: leer [`AGENTS.md`](AGENTS.md) antes de tocar código.
