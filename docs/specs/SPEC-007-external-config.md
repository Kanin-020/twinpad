# SPEC-007 — Config externa para juegos no integrados (modelo C)

| | |
|---|---|
| ID | SPEC-007 |
| Title | Config externa para juegos no integrados (REQ-007) |
| Version | 0.1.0 |
| Status | `Draft` |
| Owner | Eng lead |
| Implements | REQ-007 (AC-1, AC-2, AC-3) |
| Realized by | DES-XXX (`-` hasta asignar) |
| Verified by | TEST-022 |
| Last updated | 2026-10-07 |

## 1. Purpose

Define el contrato de la Config externa que habilita juegos sin SDK (modelo C): qué declara, cómo se valida, qué ocurre al activarla y cómo convive con el gamepad genérico (SPEC-004). Alcance v1 de la config: **controles táctiles** (superficie de SPEC-008); la Segunda pantalla sin SDK queda fuera de v1 (OQ de REQ-007 §11 resuelta por el dueño como alcance mínimo).

## 2. Definitions

- **Config externa**: archivo declarativo (JSON, UTF-8) que describe los controles de un juego sin integración.
- **Perfil de juego**: la Config activa para un `game` identificado.
- Ver CTX §8: Config externa, Plugin, Input táctil, Signature HID.

## 3. Preconditions

- Servidor activo (REQ-001) y juego en ejecución identificado por el usuario (la selección de proceso es de DES).
- App instalada con sesión operativa para aplicarla.

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `format` | string | exactamente `"twinpad-config-v1"` | yes |
| `game` | string | 1–64 chars, id legible del juego | yes |
| `controls` | array de 0…24 controles | cada control: `id` (1–32 chars, único), `kind` ∈ `button` \| `dpad` \| `stick`, `label` (1–16 chars), `hid` (Elemento HID de SPEC-004 §2) | yes |
| `hid` (por control) | string | nombre canónico de la Signature HID (SPEC-004 §2); `kind=stick` → `stick_l`/`stick_r`; `dpad` → `dpad`; `button` → un botón o trigger | yes |

Formato: JSON UTF-8, una sola raíz objeto, sin campos extra (deny_unknown).

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Controles activados | la superficie de REQ-008 muestra los controles declarados y envía por la Signature HID | config válida activada con juego corriendo |
| Rechazo de carga | mensaje de error específico (qué campo falló) y sin cambios en la sesión | config inválida/corrupta (REQ-007 AC-3) |
| Gamepad genérico | la Signature por defecto sigue operativa cuando no hay config | sin config para el juego (AC-2) |

## 6. States

- `C0 NoProfile`: sin config activa para el juego; gamepad genérico.
- `C1 ProfileActive`: config activa; la superficie táctil refleja `controls`.

| From | Event | To | Side effects |
|---|---|---|---|
| `C0` | activación de config válida | `C1` | la superficie se sustituye por la declarada |
| `C1` | desactivación o cierre del juego | `C0` | vuelve la superficie por defecto |
| `C1` | carga de otra config para el mismo juego | `C1` | la nueva sustituye a la vigente |

Invariant: en `C1`, todo control activo mapea a un Elemento HID válido (R4 de SPEC-004); nada envía sin asignación.

## 7. Main flow

1. El usuario selecciona la Config externa para el juego en ejecución.
2. El server/app la valida estrictamente (§4); inválida → rechazo con mensaje específico (AC-3).
3. Válida → la superficie táctil del teléfono se configura con `controls`.
4. Cada interacción con esos controles envía inputs por la Signature HID (AC-1).

## 8. Alternative flows

### 8.1 Sin config para el juego (AC-2)

- **Trigger:** juego no integrado sin Config externa disponible.
- **Flow:** no se activa nada; la Signature HID por defecto sigue operativa.
- **Result:** el usuario puede jugar con el gamepad genérico.

### 8.2 Dos configs para el mismo juego

- **Trigger:** existen dos configs con el mismo `game`.
- **Flow:** el usuario elige cuál activar; la última activada manda.
- **Result:** solo una está en `C1` a la vez.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| JSON malformado | rechazo con posición del error; sesión intacta | mensaje específico local |
| Campo faltante/extra o fuera de constraints | rechazo indicando campo y valor | mensaje específico local |
| `hid` que no existe en la Signature | rechazo indicando el control y el elemento inválido | mensaje específico local |
| Más de 24 controles | rechazo (R1) | mensaje específico local |
| `id` de control duplicado | rechazo indicando el id duplicado | mensaje específico local |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| `controls` vacío | config válida pero sin cambios visibles: equivale a gamepad genérico |
| Dos controles hacia el mismo Elemento HID | permitido (ambos envían al mismo elemento); el último estado manda |
| Config para un juego distinto al detectado | el usuario puede activarla manualmente; no se auto-aplica (R2) |
| Archivo con BOM o codificación no UTF-8 | rechazado con mensaje específico |

## 11. Business rules

- **R1:** una Config activa define ≤ 24 controles (capacidad de la Signature + superficie).
- **R2:** ninguna config se auto-aplica por detección de proceso en v1; la activación es manual del usuario (evita config equivocada de REQ-007 §8).
- **R3:** una carga rechazada jamás modifica la sesión ni la superficie vigente (AC-3).
- **R4:** los elementos `hid` válidos son exactamente los de SPEC-004 §2; no se inventan elementos.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Formato `twinpad-config-v1` | este documento §4 | JSON UTF-8 estricto |
| Elementos HID destino | SPEC-004 §2 | referenciado |
| Superficie donde se aplica | SPEC-008 | referenciado |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Activar config válida (REQ-007 AC-1)
  Given un juego sin integración corriendo
  When el usuario activa una config con button "A" → button_a y stick → stick_l
  Then ambos controles aparecen en el teléfono y envían por la Signature HID

Scenario: Config inválida (REQ-007 AC-3)
  Given una config con hid="boton_x" (elemento inexistente)
  When el usuario intenta activarla
  Then la carga se rechaza indicando "boton_x no es un Elemento HID válido"
  And la sesión y la superficie vigente no cambian

Scenario: Sin config (REQ-007 AC-2)
  Given un juego sin integración y sin config disponible
  When el usuario juega
  Then el gamepad genérico (Signature por defecto) opera normalmente
```

## 14. Invariants

- Toda config activa satisface §4 al 100 % (validación en frontera, ENG §8).
- La superficie de controles siempre refleja exactamente la config en `C1` o el default en `C0`.

## 15. What "complies" means

Cumple este SPEC quien honre §3, produzca §5, reproduzca §9, cumpla §10, mantenga §14 y pase §16. Lo no definido no es requerido.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Carga/activación/rechazo (AC-1..AC-3) | TEST-022 |

## 17. Open questions

- ¿Identificación automática del proceso del juego (para sugerir configs) o siempre manual? — owner: Eng lead — 2026-10-07 — abierta
- ¿Formato v2 (DSL, imágenes de fondo por control)? — owner: Eng lead — 2026-10-07 — abierta

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: Config externa (controles; sin segunda pantalla en v1) |
