# Change Request (CR) — CR-021 — Twinpad

| | |
|---|---|
| ID | CR-021 |
| Title | Alimentar la fusión de movimiento VPAD con muestras de sensores AndroidDSU |
| Type | Behavior change |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-24 |
| Decided | 2026-10-24 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, DES-002, DES-004, testing/traceability.md; supersedes CR-019 VPAD sensitivity behavior |
| Sections | SPEC-004 §4/§7/§10/§16; DES-002 §1/§4/§12; DES-004 §3/§4/§6/§8/§9/§10/§12/§13; TEST-083/084 |
| Current version → new version | SPEC-004 1.5.2 → 1.6.0; DES-002 0.3.1 → 0.3.2; DES-004 1.2.0 → 1.3.0 |

## 2. Requested change

**Current (SPEC-004 §4):**

```text
`gyro` es opcional y contiene orientación absoluta yaw/pitch/roll de pantalla en vueltas int16. Cemu aplica 0.5× y deriva `gyroChange` a partir de snapshots absolutos.
```

**Approved:**

```text
Sustituir `gyro` por `motion`, un objeto opcional con `timestamp_us` (tiempo monotónico del evento de sensor, microsegundos), `accel` (3 valores IEEE-754 de aceleración Android en m/s², orden X/Y/Z del sensor) y `gyro` (3 valores IEEE-754 de velocidad angular Android en rad/s, orden X/Y/Z del sensor). Android captura TYPE_ACCELEROMETER y TYPE_GYROSCOPE a SENSOR_DELAY_GAME y publica la última muestra disponible de ambos sensores con su timestamp monotónico.

Cuando Twinpad está conectado al VPAD de Jugador 1 seleccionado, Cemu convierte unidades, ejes y signos siguiendo la ruta AndroidDSU→DSUControllerProvider, y alimenta esas muestras a `WiiUMotionHandler` para actualizar MotionSample/VPADStatus. Se conserva la transformación de referencia: gyro ×0.9; acelerómetro con factor 9.8066/100 y mapeo DSU/Cemu X/Y/Z. El MotionSample de Cemu fusiona gyro y acelerómetro y produce orientación, dirección, aceleración y cambio angular. El gyro/orientación de la ruta local Cemu permanece intacto si Twinpad no está activo/seleccionado. El monitor Cemu distingue las muestras recibidas del resultado fusionado; el monitor Android puede seguir mostrando la orientación local calculada por el sistema de sensores, que ya no es un campo wire.
```

## 3. Motivation

El usuario aprobó simular mejor el Wii U GamePad reproduciendo la cadena completa de AndroidDSU: sensores Android → muestra DSU → `WiiUMotionHandler`/fusión Mahony de Cemu → `MotionSample` → VPAD. La orientación absoluta enviada actualmente no alimenta aceleración ni la fusión ya usada por mandos DSU; derivar velocidad de diferencias de ángulos no reproduce esa ruta. La referencia AndroidDSU fija gyro en 0.9 y acelerómetro en 1.0; CR-019 (0.5× sobre orientación absoluta) deja de describir el comportamiento VPAD y queda supersedido.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | No | La capacidad de input de sensores no cambia | None |
| SPEC-004 | Yes | El campo wire pasa de orientación absoluta a muestra inercial opcional con timestamp y unidades definidas; VPAD usa fusión | Actualizar §§4/7/10/16 con este CR |
| ARCH | No | Se conserva la frontera Android → sesión → Server → Plugin → VPAD | None |
| DES-002 | Yes | Captura ambos sensores físicos y emite muestras; el monitor local no define el wire | Actualizar interfaces y trazabilidad |
| DES-004 | Yes | Mapea muestras a unidades/ejes DSU, ejecuta WiiUMotionHandler solo para Twinpad activo y usa el resultado en VPAD | Actualizar flujo, datos y tests |
| TEST-077/081 y TEST-080 | Yes | La conversión de orientación absoluta/ganancia 0.5 deja de verificar la ruta activa | Marcar como supersedidos históricamente y registrar TEST-083/084 para la nueva cadena |
| Code | Yes | Android codec/sensores, tipos Rust, parser Cemu, integración MotionHandler/VPAD y monitor | Implementar sin cambiar campos no relacionados |
| Data / migrations | No | No hay persistencia; campo `motion` es opcional | None |
| Integrations / contracts | Yes | Cambia el schema de input_report y reemplaza la ganancia de orientación por datos inerciales | Actualizar productor Android y consumidor Cemu en la misma iteración |

## 5. Options considered

1. Capturar acelerómetro + giroscopio y alimentar `WiiUMotionHandler` con el mapeo AndroidDSU→DSU de referencia — seleccionado por el usuario para reproducir la cadena nativa de Cemu.
2. Conservar orientación absoluta y derivar velocidades por diferencias — descartado: no usa aceleración ni la fusión de Cemu existente.
3. Do nothing — conserva el comportamiento actual, que no reproduce la ruta DSU del GamePad.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** El usuario confirmó la cadena completa. Cemu ya integra muestras DSU (gyro en rad/s y aceleración transformada) mediante `WiiUMotionHandler`; alimentar el mismo procesador es la ruta más fiel al GamePad y evita duplicar fusión en Twinpad. La ganancia de gyro y el mapeo de aceleración se toman de la referencia AndroidDSU y no son preferencias configurables.
- **Rollout:** Misma iteración; `motion` es opcional para compatibilidad de reportes sin sensores. Productor y adaptador se actualizan juntos; no cambia el canal ni el polling del SDK.

## 7. Execution checklist

- [x] SPEC-004 y DES-002/DES-004 actualizados con versiones y changelog CR-021.
- [x] Android captura y serializa muestras inerciales; TEST-083 añadido.
- [x] Rust valida/roundtrips el nuevo contrato; TEST-083 incluido en su verificación de cadena.
- [x] Cemu mapea unidades/ejes y entrega a WiiUMotionHandler/VPAD solo mientras Twinpad está activo; TEST-084 añadido.
- [x] `testing/traceability.md` actualizado; TEST-077/081 identificados como comportamiento supersedido.
- [x] Pruebas Android/Rust y build Cemu ejecutados; queda pendiente ejecutar las aserciones de inicio de Cemu y validar el VPAD en juego físico.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-24 | Product owner + Twinpad team | Abierto y aprobado el cambio a la cadena de movimiento AndroidDSU→fusión VPAD de Cemu |
