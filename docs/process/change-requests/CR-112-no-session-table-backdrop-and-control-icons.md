# Change Request (CR) — CR-112 — Twinpad

| | |
|---|---|
| ID | CR-112 |
| Title | Sin sesión: monitor de recursos abierto sobre el tapete de mesa (no pantalla negra); Home/Select/Start con icono; botones y tema alineados con DESIGN.md; pantalla encendida con sesión |
| Type | UI change |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-24 |
| Decided | 2026-11-24 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-008 §2 (nota CR-077), SPEC-002 §7 (vista de sesión) |
| Sections | SPEC-008 §2, SPEC-002 §7 |
| Current version → new version | pendiente de aplicar en la siguiente revisión de cada SPEC |

## 2. Requested change

**Current:** sin sesión, la vista de sesión ocultaba el contenedor del video
(`drc_content`) y el monitor de recursos se pintaba como hoja a pantalla
completa sobre `design_void_canvas` opaco, con los gauges sin pintar: el
resultado era una pantalla negra sin fondo y sin datos.

**Proposed:**

```text
Sin sesión (state != STREAMING):
- el contenedor del video queda visible y muestra el tapete de mesa
  (bg_table_logo: gradiente Void Canvas → #0B0D14 con el logo al 12 %);
- el monitor de recursos se abre (estado WAITING) ocupando la pantalla,
  con el mismo tapete como fondo del panel y los gauges pintados;
- el monitor NO pinta un color plano opaco;
- el monitor se pliega y se vuelve a abrir a voluntad: sin transmisión se
  queda en COLLAPSED con el asa visible (nunca HIDDEN, que dejaba el panel
  cerrado y sin forma de reabrirlo).

Tapete y logo:
- el panel del monitor usa el tapete SIN logo (bg_table) y el tapete CON
  logo (bg_table_logo) pasa a ser el fondo de la vista del teclado
  (keyboard_overlay).

Controles del perfil (SPEC-008 §2):
- Home (button_extra_1), Select (button_select) y Start (button_start) se
  dibujan con icono Charcoal Panel en la cara blanca 92 % en lugar de la
  etiqueta de texto;
- el resto de etiquetas usa Inter Medium (peso 500), no Regular, alineado
  con DESIGN.md («weight 500 carries labels and navigation»);
- las opacidades de dibujo pasan a los valores del token (cara blanco 92 %,
  pulsado Cloud White); antes se escalaban a ~45 % y los controles se veían
  grises. Solo el control sin asignación se atenúa.

Botones XML y tema:
- `Widget.Twinpad.Button.Filled` = Header White Conversion Button: fill
  rgba(255,255,255,0.92), texto Charcoal Panel Inter 14px/600, padding 8×16,
  radio 12 px, plano (sin elevación ni sombra) y ripple Charcoal;
- `Theme.Twinpad` define los roles de color de M3 con los tokens de
  DESIGN.md (colorPrimary Cloud White, superficies Charcoal/Steel/Slate),
  porque sin ellos los componentes Material caen al morado de la línea base.

Pantalla:
- con sesión (Linked) la pantalla no se apaga aunque no haya transmisión;
  sin sesión se devuelve el control al sistema.
```

## 3. Motivation

El owner reporta que la vista sin sesión se ve negra. El tapete existía pero
se aplicaba a un contenedor que la propia vista ocultaba, y la hoja WAITING
lo tapaba con un color plano opaco. Además pide que los tres botones que en
un mando físico no llevan texto (Home, Select, Start) se dibujen con icono,
y que las etiquetas sigan el peso tipográfico de DESIGN.md.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-XXX | no | — | none |
| SPEC-002 | yes | §7 describe el fondo de la vista sin sesión | update note |
| SPEC-008 | yes | §2 nota CR-077 describe etiquetas de los controles | update note |
| ADR | no | — | none |
| TEST | no | `InsideDrawnShapeTest` no cambia de contrato | none |
| Code | yes | `MetricsSliderController`, `PairingActivity`, `ProfileSurfaceRenderer`, `themes.xml`, `fragment_session.xml`, 3 drawables nuevos (`ic_control_*`, `bg_table`) | PR scope |

## 5. Options considered

1. Pintar el tapete solo en la hoja WAITING — no cubre el caso de hoja oculta.
2. Pintar el tapete en la capa del video (visible siempre) y además en la
   hoja WAITING — elegida: el fondo existe sin depender del estado de la hoja
   y el monitor abierto no vuelve a tapar con negro.
3. Tapete con logo también en el monitor — descartado por el owner: el logo
   se recortaba entre las tarjetas de gauges; el logo va al fondo del teclado.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** Petición directa del owner en el chat del 2026-11-24.
- **Rollout:** same release

## 7. Execution checklist

- [ ] Target document(s) updated (nota en SPEC-002 §7 y SPEC-008 §2)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (no aplica: sin cambio de contrato)
- [ ] `testing/traceability.md` rows updated (no aplica)
- [ ] Code change merged (if any)

## 8. Alineación de layouts con DESIGN.md (auditoría)

Revisión de `res/layout`, `res/drawable` y `res/values` contra la escala
tipográfica, los radios y los tokens de la guía. No cambia comportamiento:
solo tipografía, radios, tokens y espaciado.

```text
Tipografía (roles de DESIGN.md; se elimina 20 px, que no es rol)
- HUD/etiquetas de gauges y chips: 13/500 (caption)        — ya aplicado
- FPS: 36/500 con tracking -0.72/36 (section-heading)      — ya aplicado
- Temperaturas CPU/GPU: 18/600 (feature-heading), antes 20/600
- Título del panel de conexión: 36/500 tracking -0.02 (section-heading),
  antes heredaba el tamaño por defecto
- Estado de conexión: 16/400 Frost Text (body); error: 14/500 Misted Slate
- Card de conexión guardada: nombre 16/500 (antes bold), dirección 13/500

Radios (cards 16 px, botones/inputs 12 px; nunca < 12 en botones)
- bg_metric_card: 12 dp → 16 dp
- bg_metrics_sheet: 20 dp → 16 dp
- Chips de navegación del drawer: 12 dp explícito (M3 por defecto usa 8)

Tokens
- bg_control: `#E60F1219` → `design_charcoal_panel_soft`
- Sin colores hexadecimales sueltos en layouts ni drawables

Espaciado (base 8 px, card padding 16 px)
- panel del monitor: padding 12 → 16 dp; asa 4/10 → 8/8 dp
- lista de conexiones guardadas: padding horizontal 12 → 16 dp
- botón Cerrar del drawer: 16 dp de separación de la última fila
```

## 9. Cruceta más grande y arriba-izquierda (ronda 3)

Pedido del owner en el chat del 2026-11-24: «ampliar un poco más la cruceta
y moverla un poco más arriba e izquierda».

```text
profile_map.xml · dpad_cross
- tamaño: width_percent 0.27 → 0.30, height_percent 0.30 → 0.32
  (el radio del plato es el lado menor; en pantalla landscape el diámetro
   pasa de 0.30 a 0.32 de la altura, ~+7 %)
- posición: horizontal_bias 0.088 → 0.049 y vertical_bias 0.2879 → 0.26
  centro cx 0.1992 → 0.1843 y cy 0.3515 → 0.3368
- la cruceta deja de compartir fila con el clúster de cara Y/X/A/B
  (queda ~1.5 % por encima y a la izquierda); el clúster no se mueve
```

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-24 | Buffy | Opened and approved by owner |
| 0.2.0 | 2026-11-24 | Buffy | §8: auditoría de layouts/drawables contra DESIGN.md (escala, radios, tokens, espaciado) |
| 0.3.0 | 2026-11-24 | Buffy | §9: cruceta más grande y desplazada arriba-izquierda (pedido del owner) |
