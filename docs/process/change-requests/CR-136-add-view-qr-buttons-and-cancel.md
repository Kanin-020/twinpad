# Change Request (CR) — CR-136 — Twinpad

| | | |
|---|---|---|
| ID | CR-136 |
| Title | Android: la vista de agregar ofrece QR/código con cualquier estado (también U6) y «Cancelar» queda visible sobre la cámara |
| Type | `Correction` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-27 |
| Decided | 2026-11-27 |
| Decided by | Owner (petición en la conversación) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-002 |
| Sections | §1 (UI de vinculación, sesión y conexiones guardadas), §12 (traceability) |
| Current version → new version | `0.9.0 → 0.10.0` |

## 2. Requested change

**Current:**

```text
- Tras CR-135, «Nueva conexión» en U6 (Reconectando) se queda solo con el
  título: no pinta «Escanear QR» ni «Vincular sin cámara», y el botón de
  escanear delega en la máquina, que ignora un escaneo en U6.
- Con la cámara abierta, «Cancelar» está en la cadena debajo de un preview de
  320 dp; en landscape queda empujado fuera de la pantalla y no se puede
  cancelar el escaneo.
```

**Proposed (petición del owner):**

```text
- La vista de agregar deja de depender del estado de sesión: título, «Escanear
  QR» y «Vincular sin cámara» están siempre que no haya un intento en curso
  (Scanning/Handshaking muestran preview/progreso y Cancelar).
- Con sesión viva, el QR/código pedido en U4 o U6 se registra como pendiente
  (CR-108/CR-134) y la sesión no se interrumpe; en U6 la reconexión sigue.
- «Cancelar» se ancla al borde inferior del panel con elevación, de modo que se
  ve y se puede pulsar con la cámara abierta en landscape.
- El preview de QR pasa a modo `compatible` (TextureView): en modo
  `performance` la SurfaceView se dibuja en una capa por encima de las vistas
  y seguía tapando «Cancelar» en algunos dispositivos.
```

## 3. Motivation

El owner reporta que en la vista de agregar no aparecen los botones de vincular
por QR o código (se comportaba como la vista de sesión al reconectar) y que
falta el botón de cancelar cuando se abre la cámara del escaneo. La causa es la
rama `Reconnecting` de `renderState`, que ocultaba los botones, y la cadena de
`btn_cancel` bajo el preview de 320 dp en `fragment_pairing.xml`.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | El flujo de vinculación no cambia | none |
| SPEC-002 | no | §6/§9 sin cambios; el enlace pendiente ya lo define CR-108 | none |
| ARCH | no | Sin cambios de componentes ni de red | none |
| DES-002 | yes | La vista de agregar deja de depender del estado y Cancelar se ancla abajo | update 0.10.0 |
| TEST-0207, TEST-0233 | no | Enlace pendiente intacto | none |
| TEST-0235 | yes | Nuevo test: QR en U6 queda pendiente y la máquina sigue en U6 | añadido |
| Code | yes | `pairing/PairingViewModel`, `ui/PairingActivity`, `fragment_pairing.xml` | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Sin cambios de wire ni de servidor | none |

## 5. Options considered

1. QR/código siempre visibles y pendientes en U4/U6; Cancelar anclado abajo —
   **elegida**: la vista de agregar no depende de la sesión y el escaneo se
   puede cancelar.
2. Mantener los botones ocultos en U6 — descartada: el owner ve la vista de
   agregar vacía y cree que sigue ligada a la otra vista.
3. Cancelar en una fila sobre el preview — descartada: con el preview de 320 dp
   el problema de espacio se repite.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** petición directa del owner sobre la vista de agregar y el
  botón de cancelar del escaneo.
- **Rollout:** cambio local de la app; sin cambios de protocolo, datos ni
  servidor.

## 7. Execution checklist

- [x] Target document(s) updated (DES-002 0.10.0, changelog con este CR)
- [x] Dependent documents updated (DES-002 §1/§12)
- [x] Tests added (TEST-0235 en `PairingViewModelTest`)
- [x] `testing/traceability.md` rows updated (fila TEST-0235 + historial de cambios)
- [x] Indexes (`_index.md`) updated
- [x] Code applied

## Nota de verificación

Sin emulador/dispositivo en el entorno del agente, el apilado de la cámara y el
botón no se pudo ver renderizado; la corrección se apoya en el modo de
implementación documentado de `PreviewView` y en el anclaje inferior.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-27 | Buffy (agente) | Abierto y aplicado por petición del owner |
| 0.2.0 | 2026-11-27 | Buffy (agente) | «Cancelar» seguía tapado: el preview pasa a `implementationMode="compatible"` (TextureView) para respetar el orden de dibujo |
