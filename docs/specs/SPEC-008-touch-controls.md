# SPEC-008 — Superficie de controles táctiles configurables

| | |
|---|---|
| ID | SPEC-008 |
| Title | Controles táctiles configurables en la app (REQ-008) |
| Version | 0.1.0 |
| Status | `Draft` |
| Owner | Eng lead |
| Implements | REQ-008 (AC-1, AC-2, AC-3) |
| Realized by | DES-XXX (`-` hasta asignar) |
| Verified by | TEST-023 |
| Last updated | 2026-10-07 |

## 1. Purpose

Fija el comportamiento observable de la superficie de controles táctiles del teléfono y su editor: agregar/mover/asignar controles, guardar perfiles por juego, cargarlos automáticamente y advertir conflictos (REQ-008 AC-1..AC-3). Los inputs generados viajan por la Signature HID (SPEC-004).

## 2. Definitions

- **Control táctil**: elemento interactivo de la superficie: `button`, `stick` (joystick virtual) o `dpad`.
- **Asignación**: el Elemento HID de SPEC-004 §2 al que un control envía.
- **Perfil**: el conjunto de controles + posiciones + asignaciones de un juego, guardado en el dispositivo.
- Ver CTX §8: Input táctil.

## 3. Preconditions

- La **edición** puede hacerse sin sesión (offline, en el dispositivo).
- El **uso** requiere sesión operativa (SPEC-001) y canal HID (SPEC-004).

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| Acción de editor | evento de usuario | agregar / mover / redimensionar / asignar / eliminar un control | editor |
| Perfil a aplicar | identificador de juego | el juego detectado o elegido (SPEC-006/007) | uso |
| Interacción táctil | evento de pantalla | toque/arrastre sobre un control | uso |

Restricciones del perfil: ≤ 24 controles (capacidad de SPEC-004); cada control con exactamente una asignación o ninguna; posiciones en coordenadas normalizadas 0…1 de la superficie.

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Superficie operativa | controles del perfil visibles y capturando | perfil activo con sesión (AC-1) |
| Perfil guardado | persistente en el dispositivo, asociado al `game`; exportable | acción de guardar |
| Carga automática | el perfil del juego detectado se activa al iniciar sesión | perfil existente para ese juego (AC-2) |
| Advertencia de conflicto | aviso visible; el control sin asignar se muestra atenuado y no envía | control sin asignar (AC-3) |

## 6. States

- `S0 Editing`: la app permite editar la superficie; no se envían inputs.
- `S1 InSession`: sesión activa con perfil aplicado; los controles capturan y envían.

| From | Event | To | Side effects |
|---|---|---|---|
| `S0` | guardar perfil | `S0` | perfil persistido |
| `S0` | sesión establecida con juego detectado | `S1` | se aplica el perfil del juego (AC-2) o el default |
| `S1` | sesión terminada / app a segundo plano | `S0` | la superficie deja de enviar; el perfil activo se recuerda |

Invariant: en `S1`, cada control operativo tiene asignación válida (SPEC-004 R4); los sin asignar no envían.

## 7. Main flow

1. El usuario entra a modo edición y agrega un control (p. ej. `button`).
2. Lo posiciona/redimensiona y lo asigna a un Elemento HID (p. ej. `button_a`) (AC-1).
3. Guarda el perfil asociado al juego.
4. En la próxima sesión, al detectar el juego, la app carga automáticamente ese perfil (AC-2).
5. Los toques sobre los controles generan inputs por la Signature HID (SPEC-004 §7).

## 8. Alternative flows

### 8.1 Control sin asignar (AC-3)

- **Trigger:** un control del perfil activo no tiene asignación.
- **Flow:** al activar el perfil, la app advierte el conflicto y lo muestra atenuado.
- **Result:** el control no envía inputs hasta que el usuario lo asigne.

### 8.2 Import de configuración

- **Trigger:** el usuario importa un perfil de otro dispositivo o de la comunidad.
- **Flow:** el perfil se valida con las mismas restricciones (≤ 24, asignaciones válidas de SPEC-004 §2); inválido → rechazo con detalle, sin modificar el perfil vigente.
- **Result:** perfil importado disponible; requiere activación del usuario.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| Asignación a un Elemento HID inexistente | el editor rechaza la asignación | mensaje en el editor |
| Perfil con > 24 controles al guardar/importar | rechazo indicando el límite | mensaje |
| Corrupción del perfil persistido | se descarta el perfil corrupto; se aplica el default; aviso al usuario | aviso |
| Import inválido | rechazo con detalle; perfil vigente intacto | mensaje |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Dos controles superpuestos | permitido; el superior (orden de capa) captura el toque |
| Control fuera de los límites de pantalla al importar | se clampea a coordenadas válidas |
| Dos perfiles para el mismo juego | el usuario elige el activo; la carga automática usa el último guardado |
| Cambio de orientación en `S1` | la superficie re-posiciona según coordenadas normalizadas; sin perder estado de pulsación |
| Juego sin perfil guardado | se aplica la superficie por defecto (gamepad genérico) |

## 11. Business rules

- **R1:** todo control operativo mapea a exactamente un Elemento HID de SPEC-004 §2; sin asignación no envía (AC-3, SPEC-004 R4).
- **R2:** el perfil de un juego detectado se carga automáticamente si existe (AC-2); sin perfil, default.
- **R3:** la edición ocurre en `S0`; nunca se envían inputs durante la edición (NFR-002: la ruta de inputs no se toca al editar).
- **R4:** los perfiles son portables (export/import) con validación idéntica a la local.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Elementos HID asignables | SPEC-004 §2 | referenciado |
| Formato de Config externa que genera perfiles | SPEC-007 §4 | v1 |
| Autenticación y sesión | SPEC-001/002 | referenciados |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Agregar y asignar un botón (REQ-008 AC-1)
  Given la app en modo edición
  When el usuario agrega un button, lo posiciona y lo asigna a button_b
  And guarda el perfil para "pacdemo"
  Then en la próxima sesión de "pacdemo" el botón aparece y envía button_b

Scenario: Carga automática (REQ-008 AC-2)
  Given un perfil guardado para "pacdemo"
  When "pacdemo" se detecta en una nueva sesión
  Then el perfil se aplica automáticamente sin pasos del usuario

Scenario: Control sin asignar (REQ-008 AC-3)
  Given un perfil con un button sin asignación
  When el usuario activa el perfil
  Then la app advierte el conflicto, el control se ve atenuado y no envía inputs
```

## 14. Invariants

- Ningún control sin asignación envía inputs en ningún estado.
- El perfil activo en `S1` siempre coincide con el último aplicado para el juego detectado.

## 15. What "complies" means

Cumple este SPEC quien honre §3, produzca §5, reproduzca §9, cumpla §10, mantenga §14 y pase §16. Lo no definido no es requerido.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Edición, asignación, carga automática y conflicto (AC-1..AC-3) | TEST-023 |

## 17. Open questions

- ¿Editor solo en el celular o también desde la PC? — owner: Eng lead — 2026-10-07 — abierta
- ¿Formato de export (mismo JSON de SPEC-007 u otro)? — owner: Eng lead — 2026-10-07 — abierta

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: superficie táctil configurable y perfiles |
