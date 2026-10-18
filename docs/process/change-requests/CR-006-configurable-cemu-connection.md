# Change Request — CR-006 — Conexión Twinpad de Cemu configurable

| | |
|---|---|
| ID | CR-006 |
| Title | Conexión Twinpad de Cemu configurable |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Twinpad team (propuesta solicitada por el owner) |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (usuario) |

> Aprobado por el Product owner el 2026-10-18: conexión automática apagada inicialmente, opción persistente en una ventana de ajustes, efecto inmediato sin desconectar, conexión manual solo durante un título y dos opciones de menú con los textos indicados.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, ARCH, DES-004; CR-005 sigue vigente para captura, audio, inputs y reintento de una conexión iniciada |
| Sections | SPEC-006 §3/§7/§9/§11/§13/§16; ARCH §2/§3/§6; DES-004 §1/§3/§6/§7/§8/§12; menú y configuración de Cemu |
| Current version → new version | SPEC-006 1.3.0 → 1.4.0; ARCH 0.3.0 → 0.4.0; DES-004 0.1.0 → 0.2.0 |

## 2. Requested change

**Current — CR-005 / SPEC-006 §7:**

```text
Cuando un título Cemu comienza, el adaptador Twinpad intenta conectarse al Servidor PC automáticamente; reintenta y captura automáticamente mientras el título esté activo.
```

**Proposed — aprobado:**

```text
- La conexión automática de Cemu es una preferencia persistente, inicialmente false. Se configura en una ventana de ajustes abierta desde “Configuraciones”.
- Al activar la preferencia con un título activo, la conexión comienza inmediatamente. Al desactivarla no se desconecta una conexión existente; la preferencia solo evita la conexión automática para títulos posteriores.
- “Iniciar conexión” inicia la conexión y captura solo si hay un título activo. Sin título, la opción está deshabilitada. Iniciar manualmente funciona aunque la conexión automática esté apagada.
- El menú Twinpad contiene exactamente dos opciones: “Iniciar conexión” y “Configuraciones”.
- La conexión/reintento, captura GamePad de hasta 5 fps, audio DRC, input forwarding, y desconexión al terminar el título mantienen los contratos de CR-005.
```

## 3. Motivation

El Product owner requiere que Cemu no inicie una conexión de Twinpad automáticamente por defecto, pero que el usuario pueda habilitar ese comportamiento. También solicita un inicio manual explícito y que el menú de Cemu presente solo las dos acciones indicadas.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003/004/006 | No | El cambio solo define el control de conexión del adaptador experimental Cemu | None |
| SPEC-006 | Yes | La excepción Cemu pasa de conexión automática incondicional a preferencia persistente + inicio manual | Actualizar flujo, reglas, ejemplos y verificación |
| ARCH | Yes | La UI de Cemu es responsable de iniciar el adaptador por preferencia o petición manual | Actualizar límites e integración |
| DES-004 | Yes | Separar estado de título de inicio del adaptador y mantener conexiones activas al desactivar auto-connect | Actualizar lifecycle/UI/config |
| TEST-046 | Yes | Verificar persistencia, inicio manual condicionado al título, auto-connect y no-desconexión al desactivar | Registrar suite de aceptación; ejecución manual Cemu pendiente |
| Code | Yes | Guardar preferencia, abrir ajustes, crear los dos elementos exactos y condicionar start/capture | Implementar en Cemu |
| Data / migrations | No | XML usa false cuando la nueva clave aún no existe; no requiere migración | None |
| Integrations / contracts | No | No cambia el protocolo localhost, sesiones ni wire format | None |

## 5. Options considered

1. Preferencia apagada por defecto, conexión manual disponible y auto-connect inmediatamente configurable sin tumbar una sesión existente — cumple la solicitud y evita desconexiones inesperadas.
2. Mantener la conexión automática obligatoria de CR-005 — incumple el requerimiento del Product owner.

## 6. Decision

- **Outcome:** `Approved` por el Product owner el 2026-10-18.
- **Rationale:** la conexión es una acción controlada por el usuario: puede iniciarse explícitamente y el inicio automático es opcional, persistente y opt-in. Cambiar esa preferencia no interrumpe una conexión ya establecida.
- **Rollout:** build local experimental de Cemu; sin cambios en compatibilidad general de juegos/SDK.

## 7. Execution checklist

- [x] SPEC-006, ARCH y DES-004 actualizados con CR-006
- [x] TEST-046 registrado en la trazabilidad y definido como prueba de aceptación
- [x] Preferencia `twinpad_auto_connect` persistida y false por defecto
- [x] Menú Twinpad contiene exactamente “Iniciar conexión” y “Configuraciones”
- [x] Inicio manual solo con título activo; ajustes abren una ventana modal
- [x] Habilitar auto-connect con título activo inicia inmediatamente; deshabilitarlo no desconecta
- [ ] Build Cemu y revisión del diff
- [ ] Validación manual de UI/configuración en Cemu

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-18 | Product owner + Twinpad team | Aprobado: preferencia persistente opt-in, inicio manual y menú Twinpad de dos opciones |
