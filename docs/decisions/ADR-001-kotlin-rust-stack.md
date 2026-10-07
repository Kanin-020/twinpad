# ADR-001 — Usar Android nativo en Kotlin con Rust para las rutas críticas

| | |
|---|---|
| ID | ADR-001 |
| Title | Android nativo (Kotlin) + Rust para rutas críticas |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Twinpad team (entrevista de definición) |
| Affects | ARCH §2, DES de streams, DES del Motor HID |

## 1. Context

Twinpad necesita, en el celular, capturar inputs de baja latencia (táctiles y sensores), emitir un Gamepad HID por Bluetooth y renderizar Framebuffers de la Segunda pantalla. En la PC necesita capturar framebuffer/audio y gestionar transporte de red. La entrevista planteó explícitamente el uso de C++/Rust "para lo crítico/rápido" y Java/Kotlin para el resto (CTX-C4). Android 12+ es el piso soportado (CTX-C1).

## 2. Problem

¿Qué lenguajes y capas usamos para implementar la app Android y los componentes de PC, equilibrando acceso a APIs del sistema (UI, sensores, Bluetooth) con rendimiento en framebuffers, HID y networking?

## 3. Options considered

### Option A — App Android completa en C++/Rust (NDK + UI no estándar)

- **Pros:** un solo lenguaje de rendimiento; control total de memoria.
- **Cons:** UI y APIs de Android (Bluetooth HID device role, vibración, SurfaceView) requieren bindings manuales; ciclo de desarrollo mucho más lento; riesgo alto en permisos y compatibilidad por fabricante.

### Option B — Kotlin como lenguaje principal + Rust (vía JNI/NDK) para lo crítico

- **Pros:** acceso directo y estable a APIs de Android (UI, sensores, BT, foreground service); Rust cubre framebuffers, códecs, transporte e hid en el hot path; el límite FFI es una frontera clara y testeable.
- **Cons:** dos lenguajes y una frontera JNI que mantener; build más complejo (cargo + gradle).

### Option C — Todo en Kotlin (sin Rust)

- **Pros:** stack único; iteración más rápida al inicio.
- **Cons:** riesgo de no cumplir NFR-001/NFR-002 (latencias) en captura/encode/transporte; GC pauses en hot paths de video.

## 4. Decision

Usaremos Kotlin como lenguaje principal de la app Android y de la integración con el sistema, con Rust para los componentes críticos de rendimiento (transporte, framebuffers, codificación, Motor HID), porque cubre las NFR de latencia sin sacrificar el acceso nativo a las APIs de Android.

## 5. Justification

- NFR-001 y NFR-002 exigen latencias acotadas en las rutas calientes → Option C descartada como base (aunque Kotlin siga en UI y orquestación).
- CTX-C1 (Android 12+) y los permisos de BT HID hacen inviable Option A (costo de bindings desproporcionado).
- CTX-C4 ya fija la intención del equipo: Kotlin + Rust.
- La frontera FFI (DES correspondiente) permite testear Rust de forma aislada y determinista (ENG P10/P11).

## 6. Consequences

**Positive:**
- Hot paths en Rust, testeables sin dispositivo.
- Acceso nativo a APIs Android sin bindings artesanales.

**Negative / trade-offs accepted:**
- Frontera JNI que mantener y versionar.
- Toolchain doble (Gradle + Cargo) en CI.

**Neutral:**
- El equipo necesita convivencia Kotlin/Rust en el mismo módulo.

## 7. Compliance and validation

- Enforcement: revisión de arquitectura + estructura de módulos (ENG §2); import-boundary check en CI.
- Revisit trigger: si NFR-002 resulta inalcanzable con Rust+JNI, o si el mantenimiento de la frontera FFI domina el costo.

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | NFR-001, NFR-002, CTX-C1, CTX-C4 |
| Contextualized in | ARCH §2 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
