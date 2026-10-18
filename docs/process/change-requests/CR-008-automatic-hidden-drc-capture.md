# Change Request — CR-008 — Captura automática y oculta del DRC para Twinpad

| | |
|---|---|
| ID | CR-008 |
| Title | Captura automática y oculta del DRC para Twinpad |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Twinpad team (solicitada por el Product owner) |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (usuario) |

> Aprobado por el Product owner: Twinpad debe transmitir automáticamente imagen y sonido del GamePad sin requerir “Separate GamePad View” ni preguntar qué pantalla transmitir. Se eligió capturar internamente el DRC, sin abrir esa ventana.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, ARCH, DES-004 |
| Sections | SPEC-006 §7/§9/§11/§13/§16; ARCH §2/§3/§6; DES-004 §1/§6/§7/§8/§12 |
| Current version → new version | SPEC-006 1.5.0 → 1.6.0; ARCH 0.5.0 → 0.6.0; DES-004 0.3.0 → 0.4.0 |

## 2. Requested change

**Current — SPEC-006 §7 / CR-005:**

```text
Cemu publica textura GamePad limitada a 5 fps; el capture DRC está ligado a la ruta de render de la vista separada y la selección de fallback de video permanece según SPEC-003.
```

**Proposed — aprobado:**

```text
- Al iniciar una conexión Twinpad para un título activo, el adaptador obtiene automáticamente la textura del framebuffer DRC/GamePad, aunque “Separate GamePad View” esté cerrada. La captura se realiza internamente desde el render target DRC, sin mostrar ni activar la vista separada.
- Twinpad envía esa imagen DRC y el audio PCM DRC ya conectado, sin solicitar al usuario elegir monitor/pantalla ni presentar una pregunta de selección.
- Se conserva el límite de captura de video de CR-004: máximo 5 fps. No se modifica la salida visual normal de Cemu.
- La opción de Cemu “Separate GamePad View” conserva su función normal independiente y queda disponible para depuración/pruebas de transmisión; no es un requisito para Twinpad.
- Si no hay título activo o conexión/captura Twinpad solicitada, no se hace readback DRC para Twinpad. El fallback de stream de SPEC-003 se conserva solo para pruebas de transmisión y no reemplaza el origen DRC del adaptador Cemu conectado.
```

## 3. Motivation

El Product owner solicita que Twinpad pase directamente la imagen y sonido del GamePad sin activar la vista separada ni preguntar qué pantalla transmitir. La revisión del código confirmó que el render target DRC está disponible en `LatteRenderTarget_itHLECopyColorBufferToScanBuffer`, pero el readback Cemu actual solo se ejecuta cuando `IsPadWindowActive()`; el audio DRC ya se publica por el cliente al conectarse.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | No | Se mantiene el contrato genérico de streams | None |
| SPEC-006 | Yes | El origen Cemu pasa a captura DRC interna, independiente de la ventana | Actualizar flujo, reglas, ejemplos y verificación |
| ARCH | Yes | El adaptador Cemu consume la textura DRC en el límite de render, sin UI de selección | Actualizar responsabilidades/límites |
| DES-004 | Yes | Añadir callback de captura DRC oculta con rate limit y ciclo de vida | Actualizar diseño |
| TEST-048 | Yes | Verificar que el adaptador capture DRC con vista cerrada, no abra vista ni pregunte y mantenga el límite 5 fps | Registrar aceptación/build; E2E con título real pendiente si no está disponible |
| Code | Yes | Desacoplar readback DRC del render de la ventana; retener audio y selector como herramientas de pruebas | Implementar en submódulo Cemu |
| Data / migrations | No | Sin persistencia nueva | None |
| Integrations / contracts | Yes | Sin cambios de wire, Pairing, audio o endpoint; cambia el disparador interno del framebuffer DRC | Sin versionado de protocolo |

## 5. Options considered

1. Captura interna del render target DRC sin ventana — cumple el flujo directo solicitado y evita cambios visuales de Cemu.
2. Abrir automáticamente “Separate GamePad View” — rechazada por la decisión del usuario de no mostrarla.
3. Mantener selección manual de pantalla — no entrega automáticamente el DRC a Twinpad.

## 6. Decision

- **Outcome:** `Approved` por el Product owner el 2026-10-18.
- **Rationale:** el usuario eligió explícitamente captura interna oculta para Twinpad. El readback solo se activa cuando el cliente solicita captura durante un título y conserva el máximo de 5 fps; el selector normal de Cemu queda fuera del camino de uso de Twinpad.
- **Rollout:** build experimental local de Cemu; no cambia formatos de red ni el comportamiento estándar de la vista separada.

## 7. Execution checklist

- [ ] SPEC-006, ARCH y DES-004 actualizados (CR-008)
- [ ] TEST-048 registrado en trazabilidad
- [x] Readback DRC independiente de “Separate GamePad View” implementado
- [x] Confirmado máximo 5 fps y ausencia de selección/ventana en el flujo Twinpad
- [x] Server espera el primer frame DRC para una instancia Cemu viva en vez de usar desktop/demo, y habilita su audio DRC desde el inicio
- [x] TEST-049 registra el comportamiento del adapter vivo antes del primer frame
- [x] Build Cemu y pruebas Rust pertinentes completadas
- [ ] Smoke end-to-end con título real (pendiente: no hay un título de prueba disponible en este entorno)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-18 | Product owner + Twinpad team | Aprobada captura directa y oculta del DRC para Twinpad; la vista separada queda solo como herramienta normal de depuración/pruebas |
