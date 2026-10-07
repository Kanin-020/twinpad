# ADR-006 — Soportar plugins vía SDK Twinpad (B) y Config externa (C)

| | |
|---|---|
| ID | ADR-006 |
| Title | Plugins: SDK Twinpad (B) + Config externa (C) |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Twinpad team (entrevista de definición) |
| Affects | ARCH §2, §6; REQ-006; REQ-007 |

## 1. Context

La entrevista (pregunta 6) preguntó dónde vive el plugin: (A) en la PC como .dll/.so/.jar que carga la app del juego, (B) integrado en el código del juego vía SDK, o (C) como configuración externa (JSON/DSL) sin tocar el juego. El usuario respondió combinando: "Plugins: por juego, mediante Twinpad SDK integrado en la app/PC del juego (B) + configuración externa (C) para juegos que no pueden modificar su código", codificado en CTX-C7.

## 2. Problem

¿Cómo se integra un juego o aplicación con Twinpad sin crear un mantenimiento específico por título dentro del Core?

## 3. Options considered

### Option A — Plugin binario cargado por Twinpad (.dll/.so)

- **Pros:** no requiere tocar el juego ni el SDK en su código.
- **Cons:** carga dinámica de binarios de terceros (riesgo de seguridad, ABI frágil); firma/aislamiento complejos en Windows.

### Option B — SDK Twinpad integrado en el juego

- **Pros:** contrato explícito y versionable; el juego anuncia contenido y firma el flujo; detección limpia (REQ-006).
- **Cons:** exige modificar juegos que pueden hacerlo (no conveniencia de todos los títulos).

### Option C — Config externa (JSON/DSL)

- **Pros:** cero cambios de código en el juego; la comunidad puede crear configs; validación estricta posible (REQ-007).
- **Cons:** capacidades limitadas (solo lo que el SO/Core permite sin cooperación del juego).

## 4. Decision

Usaremos un modelo dual de plugins: SDK Twinpad (Opción B) para juegos integrados y Config externa (Opción C) para juegos no modificables, y no permitiremos carga dinámica de binarios de terceros (Opción A) en la v1.

## 5. Justification

- CTX-C7 lo fija como decisión ("SDK integrado (opción B) + configuración externa (opción C)").
- La Opción B da el flujo completo (segunda pantalla + inputs acordados); la C cubre el resto con gamepad estándar (REQ-007).
- Descartar la Opción A reduce superficie de ataque (ejecutar binarios de terceros) y fragilidad de ABI, alineado con ARCH §7.

## 6. Consequences

**Positive:**
- Cobertura: juegos integrados (experiencia completa) y no integrados (gamepad sin instalar nada).
- Códigos de integración auditables (SDK versionado; Configs validadas).

**Negative / trade-offs accepted:**
- Doble superficie de integración que documentar y testear.
- Los juegos sin integración no tendrán segunda pantalla garantizada (limitación en REQ-007).

**Neutral:**
- El formato de Config externa queda sujeto a SPEC (no definido aquí).

## 7. Compliance and validation

- Enforcement: revisión de arquitectura; límites de ARCH §3 (el Cargador de Config externa no ejecuta código del juego).
- Revisit trigger: si la Opción A se vuelve necesaria (demanda de comunidad de plugins binarios), nueva ADR que supersede esta.

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | REQ-006, REQ-007, REQ-003 |
| Contextualized in | ARCH §2, §6 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
