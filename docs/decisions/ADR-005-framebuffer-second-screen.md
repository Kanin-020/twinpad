# ADR-005 — Transmitir la Segunda pantalla como framebuffers (no HTML/UI)

| | |
|---|---|
| ID | ADR-005 |
| Title | Segunda pantalla por framebuffers |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Twinpad team (entrevista de definición) |
| Affects | ARCH §2, §4; REQ-004; REQ-005 |

## 1. Context

La entrevista (pregunta 5) planteó tres formas de transmitir la Segunda pantalla: (1) framebuffers/imágenes — "como una pantalla que se actualiza, similar al GamePad Pro de CEMU", (2) contenido HTML/UI renderizado en una mini-webview, o (3) datos estructurados que el celular decide cómo mostrar. El usuario respondió: "Imágenes y framebuffers (como GamePad Pro en CEMU pero genérico)", codificado en CTX-C6.

## 2. Problem

¿Qué representación del contenido de Segunda pantalla viaja de la PC al celular?

## 3. Options considered

### Option A — Framebuffers / imágenes

- **Pros:** genérico: cualquier cosa que la PC pinte puede viajar; el cliente es un visor simple; consistente con el pipeline de captura (ARCH §2); sin motor de UI en el cliente.
- **Cons:** más ancho de banda que datos estructurados; el celular no puede re-escalar UI de forma nativa (solo la imagen); interacción táctil sobre la pantalla requiere mapeo de coordenadas.

### Option B — Contenido HTML/UI (mini-webview)

- **Pros:** UI adaptable al dispositivo; bajo ancho de banda.
- **Cons:** motor web en el cliente (peso, NFR-005/NFR-007); superficie de ataque mayor; acoplamiento a un renderer.

### Option C — Datos estructurados (plugin decide en PC, celular renderiza)

- **Pros:** mínimo ancho de banda; UI nativa por juego.
- **Cons:** trabajo por juego en el cliente; contradice el objetivo "genérico" de la entrevista.

## 4. Decision

Usaremos framebuffers/imágenes como representación de la Segunda pantalla para toda la v1, porque es la decisión tomada en la entrevista y la más genérica respecto de los juegos integrados.

## 5. Justification

- CTX-C6 lo fija explícitamente ("framebuffers / imágenes (no HTML)").
- Simplifica el cliente Android (un visor) en línea con NFR-005 (CPU) y NFR-007 (batería).
- El modelo de Plugins (ADR-006) define el contenido; el transporte solo mueve píxeles → separación limpia de responsabilidades (ARCH §2).
- Las alternativas B/C quedan como evolución futura documentada en CTX §4.

## 6. Consequences

**Positive:**
- Un único pipeline de captura → transporte → render para todos los juegos.
- El contenido está desacoplado de la implementación del cliente.

**Negative / trade-offs accepted:**
- Ancho de banda mayor (mitigado por codificación de video, DES del stream).
- La legibilidad del texto pequeño depende de la resolución elegida (política de degradación en DES).

**Neutral:**
- El mapeo de toques sobre la imagen (si aplica) es responsabilidad del SPEC de controles, no del stream.

## 7. Compliance and validation

- Enforcement: revisión de arquitectura; contrato de Framebuffer referenciado desde el SPEC del stream (no duplicado).
- Revisit trigger: si NFR-001 resulta inalcanzable con video comprimido, o si surge demanda fuerte de UI adaptable.

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | REQ-004, REQ-005, NFR-001, NFR-005 |
| Contextualized in | ARCH §2, §4 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
