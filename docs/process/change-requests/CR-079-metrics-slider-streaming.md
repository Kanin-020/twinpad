# Change Request (CR) — CR-079 — Twinpad

| | |
|---|---|
| ID | CR-079 |
| Title | Slider de gauges durante la sesión: la ventana de espera se convierte en hoja arrastrable sobre el stream; el toggle del drawer pasa a «Mostrar slider de métricas» |
| Type | `Feature` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-05 |
| Decided | 2026-11-05 |
| Decided by | Product owner (respuestas en la sesión del 2026-11-05) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §6 («Mostrar slider de métricas» en el drawer — update pendiente, patrón CR-077), `activity_pairing.xml`, `drawer_settings.xml`, código Android (`MetricsSliderController` nuevo, `PairingActivity`, `SettingsDrawerController`, `AndroidSettingsStore`, tests), `testing/traceability.md` |
| Sections | Ventana de espera CR-076/v3, drawer SPEC-002, ventana completa del GamePad (SPEC-002 §5) |
| Current version → new version | SPEC-002 nota sin bump (patrón CR-077); sin cambios de wire |

## 2. Requested change (owner)

```text
1. Cuando hay una sesión de juego se tienen que poder mostrar las
   métricas con un slider que esté en la parte baja de la pantalla, y
   cambia «mostrar métricas del PC» por «mostrar slider de métricas».
2. Aclaraciones del owner (misma sesión):
   - «EL slider debe ser la misma ventana que la que se muestra cuando
     no hay conexión, solo que se pueda subir y bajar por encima cuando
     haya transmisión; cuando no haya [transmisión] se muestra principal
     como ahora».
   - Ventana de espera: «Igual que ahora».
   - Al entrar en sesión: «Abierto (visible)».
```

## 3. Decisiones (desarrollador, asentadas para el review)

1. **Un solo panel**: la retícula 2×2 de gauges de CR-076/v3 es ÚNICA en la
   jerarquía. Sin stream ocupa la pantalla completa como vista frontal de
   espera (fondo Void Canvas, con `txt_games` en INACTIVE) — vista «como
   ahora» según el owner. En STREAMING la MISMA retícula (la única
   instancia, que vive siempre dentro de `metrics_sheet`) cambia de
   presentación: hoja inferior (`metrics_sheet`, fondo Charcoal
   con radios 20 dp arriba, asa abajo) sobre el video, por parámetros de
   layout — sin reparent de vistas.
2. **Estados del slider** (STREAMING): OPEN (nace aquí, respuesta del
   owner), draggable con el dedo (touch en toda la hoja) hasta PEEK (solo
   el asa visible en el borde inferior) y de vuelta a OPEN; al soltar
   decide por umbral 50 % del recorrido (sin animación). El drag del
   contenedor consume toques solo dentro de la hoja; fuera del stream el
   contenedor no consume.
3. **Interruptor del drawer**: la fila CR-077 «Métricas de la PC
   (desmarcado: Gamepad)» se REEMPLAZA por «Mostrar slider de métricas»
   (`settings_metrics_slider`, switch `switch_metrics_slider`) con la
   preferencia local nueva `metrics_slider_over_stream` (default
   `true` = slider activo durante la sesión). El toggle se aplica en
   caliente (~1 s, por el tick de gauges que re-consulta el ajuste).
4. **Origen de los gauges (v2 tras la prueba del owner)**: los gauges
   visibles son SIEMPRE las métricas de la PC (`host_metrics` del
   server): la rama del teléfono Gamepad sale del flujo de la app (el
   muestreo `DeviceMetricsSampler` y su test TEST-0158 permanecen como
   lógica testeada, sin camino de pintado activo) y la preferencia
   `metrics_source_host` (CR-077) ya no condiciona nada — si quedó
   persistida en false de pruebas del CR-077 ya no afecta (la fila del
   drawer salió sin posibilidad de regreso). Una UI futura para elegir
   origen será un CR propio.
5. **Z-order (v2 tras la prueba del owner)**: la superficie táctil del
   juego (`surface_container`) roba los toques del drag cuando la hoja se
   declara debajo en el XML (choque reportado en banco, 2026-11-05). El
   controlador entonces le da **elevación en runtime** al contenedor del
   slider SOLO en modo hoja (8 dp, encima de la superficie como el HUD y
   el botón de ajustes, que están tras `surface_container`); en modo
   espera la elevación vuelve a 0 y el orden declarado en XML se mantiene
   idéntico al CR-076 v3 (el HUD y el botón de ajustes siguen por encima
   de la vista de espera). `keyboard_overlay` queda declarado al final (IME por
   encima en ausencia de elevaciones ajenas): si el teclado del juego
   abre con la hoja abierta, la hoja se pliega (PEEK) para usarlo.
6. Sin stream (incluido INACTIVE/INTERRUPTED): el contenedor actúa como
   la vista de espera actual fullscreen opaca (cubre el Framebuffer
   residual, regla CR-076 v3 intacta); sin sesión no hay gauges (como
   hoy).
7. Preferencia OFF durante STREAMING: sin contenedor visible y sin
   pintado de gauges (el monitor Gamepad se apaga por el mismo gate del
   tick).
8. **Geometría del plegado (v4 tras la prueba del owner)**: el asa pasa
   a ARRIBA de la retícula (decisión del owner: «el detalle del slider
   debe estar arriba no abajo»). Con el asa abajo el plegado era
   geométricamente imposible: el tope de traslación del v3
   (`sheet.height - handle.top`) dejaba el asa pegada al borde inferior
   y el clamp del drag casi no permitía mover la hoja (queja «no me
   deja bajar el slider completamente»). Con el asa arriba el tope de
   plegado es `sheet.height - (handle.top + handle.height)`: la hoja
   baja del todo y solo el asa queda visible pegada al borde inferior;
   al soltar decide por el mismo umbral del 50 %, y se re-abre
   arrastrando el asa hacia arriba.
9. **Interacción del plegado (v5 tras la segunda prueba del owner)**:
   la hoja a fracción fija (55 %) sale del flujo: el panel es
   full-height y sigue al dedo en los dos extremos del barrido —
   arrastrando desde la parte baja hasta la parte superior de la
   pantalla queda en PANTALLA COMPLETA; arrastrando desde la parte
   superior hacia la inferior se quita (queda plegado con solo el asa
   visible pegada al borde inferior). Al soltar en medio decide por el
   umbral del 50 % del recorrido. Nace PLEGADO (solo el asa): decisión
   derivada del gesto que el owner describió (el slider se pone en
   pantalla completa arrastrando, no de entrada).
10. **Comportamiento al soltar (v6 tras los logs del banco)**: el panel
    QUEDA en la traslación del soltar, como el slider lateral que el
    owner pidió («en resumen debe funcionar como el slider lateral pero
    en la parte inferior de arriba a abajo»): el decisor del 50 % del
    v5 se retira (los logs de la prueba muestran gestos cortos
    devueltos al plegado por el snap, percibido como «se buguea» y
    «ya no lo puedo bajar»). El plegado inicial ahora se aplica SOLO
    cuando la vista ya midió (el post temprano del v5 causaba el
    «se muestra ya abierto» cuando debía verse solo el drag).
11. **Contenedor interceptor (v7 tras la tercera prueba del owner)**: el
    drag deja de depender de quién consumió el DOWN. El contenedor del
    slider (`MetricsSliderContainer`, subclase de FrameLayout) intercepta
    los desplazamientos verticales que arranquen dentro de la hoja y los
    delega al controlador (DOWN virtual con el slop mínimo); con el panel
    plegado el hit-test solo alcanza la franja visible del asa y los
    toques del juego quedan intactos fuera del panel; sin modo hoja el
    interceptor no consume nada.
    - Nota diag (2026-11-05): el owner reportó «se interrumpe la
      transmisión» + «se muestra el medidor sobre el juego»: la app
      respondió por diseño a la vista de espera fullscreen (regla
      CR-076 v3 íntegra) cuando el video/audio se cayó; en el server el
      relay UDP salió por «Pairing inactivo» con la sesión TCP aún
      mandando pings. Causa del corte queda por rastro (server) en el
      próximo intento con captura en vivo.
12. **Franja del asa transparente + gate táctil (v8 tras la cuarta prueba
    del owner, misma sesión del banco)**:
    a. **Fondo solo en el contenido**: `bg_metrics_sheet` (Charcoal) se
       pintaba en la HOJA COMPLETA y el plegado mostraba una banda gris de
       todo el ancho con el asa encima (queja: «la parte superior debe ser
       transparente, solo se debe ver el drag»). El fondo pasa vivir SOLO
       en el contenido nuevo `metrics_content` (retícula + `txt_games`,
       mismo peso del layout); la franja del asa queda sin fondo y en
       plegado se ve ÚNICAMENTE el asa. En modo espera el fondo Void
       Canvas sigue en la hoja (regla CR-076 v3 intacta).
    b. **El slider solo toca donde es visible**: el contenedor fullscreen
       con elevación 8 dp se comía CUALQUIER DOWN sobre el juego cuando no
       había hijo en el punto (el controlador respondía «true» a todo el
       gesto): se interponía en los toques del juego y hasta arrastraba el
       panel (queja: «interfiere todo el touch a pesar de que solo debes
       poder arrastrar el drag y el contenido a medida que aparece, no
       antes de que aparezca»). El DOWN huérfano del contenedor ahora se
       rechaza si cae FUERA del área visible de la hoja (helper puro
       `enHojaVisible`; tope visible = traslación vigente): con el panel
       plegado solo la franja del asa arranca el drag, y el tope sube a
       medida que aparece el contenido; la continuity del gesto activo
       (CR-082) queda intacta.
13. **Ajuste automático al 100 % (v9 tras la quinta prueba del owner,
    misma sesión del banco)**: durante el drag hacia arriba, al llegar al
    90 % del recorrido (resto ≤ 10 % del tope de plegado, constante
    `FRACCION_AJUSTE_PANEL = 0.1`), la franja del asa DEJA de ser
    transparente (fondo `bg_metrics_sheet` también en la franja, la hoja
    se ve completa) y el panel se ajusta SOLO al 100 % (traslación 0);
    por debajo del umbral sigue la regla v6 (queda donde lo sueltas) y
    la franja vuelve a ser transparente (regla v8: solo el asa).
14. **Ajuste automático a la posición inicial en el cierre (v10 tras la
    sexta prueba del owner, misma sesión del banco)**: regla espejo de
    (13) en el sentido inverso — durante el drag hacia abajo (el panel
    se está quitando), cuando al panel le queda 10 % del recorrido de
    plegado o menos (traslación ≥ 90 % del tope, misma constante
    `FRACCION_AJUSTE_PANEL = 0.1`), se ajusta SOLO a la posición inicial
    (plegado completo: queda únicamente el asa, franja transparente por
    la regla v8); por encima del umbral sigue la regla v6 (queda donde
    lo sueltas). Helper puro `pliegueAutomaticoAlFinal` aplicado en el
    MOVE del drag (encadenado tras el ajuste de apertura).
15. **Umbral del ajuste automático de 10 % a 30 % (v11 tras la séptima
    prueba del owner, misma sesión del banco)**: el owner pidió subida
    en AMBOS sentidos (apertura y cierre); la constante
    `FRACCION_AJUSTE_PANEL` pasa de `0.1` a `0.3`, de modo que el ajuste
    automático se dispara cuando al panel le queda ≤ 30 % del tope de
    plegado (apertura: recorrido ≥ 70 % → snap al 100 % y franja con
    fondo; cierre: traslación ≥ 70 % del tope → snap a la posición
    inicial, plegado completo). Fuera del tramo sigue la regla v6. Las
    decisiones (13) y (14) conservan su mecánica; este threshold es el
    vigente.

12. **Animación suave (v12, octava prueba del owner)**: la hoja deja de saltar — el plegado inicial ANIMA (`0 → tope` suave al medir) y en el drag SOLO el snap del ajuste automático anima (`panelConAnimacion(bruto, target)` puro + `ANIMACION_PANEL_MS = 150`); el drag a mano queda pegado al dedo sin retardo (regla v6 intacta) y la siguiente MOVE del dedo cancela la animación del snap (el dedo manda). TEST-0179.
13. **Fila del slider en HUD (v12)**: el switch `switch_metrics_slider` sale del bloque Debug (CR-075) del drawer y pasa a ser el segundo elemento de la sección HUD, después de «Mostrar FPS» (elemento de HUD sobre el stream, decisión del owner).

## 4. Change (developer solution + update)

| | |
|---|---|
| Layout | `activity_pairing.xml`: `waiting_view` se reemplaza por `metrics_slider` (contenedor fullscreen transparente) + `metrics_sheet` (retícula, `txt_games`, asa `metrics_handle`); drawable nuevo `bg_metrics_sheet` (radios 20 dp top) y `bg_metrics_handle` (barra 4 dp) |
| Código | `MetricsSliderController` nuevo (modos WAITING/SHEET/HIDDEN, parámetros de layout del sheet por modo, drag con umbral, nacer OPEN, elevación runtime v2); `PairingActivity` alimenta `setStreaming(streaming, pref)` desde `applyStreamState` y el tick pinta con la fuente vigente cuando toca; `AndroidSettingsStore` + clave `metrics_slider_over_stream`; `SettingsDrawerController` reconecta la fila (label + pref nueva) |
| Wire | SIN cambios (los gauges siguen alimentados por `host_metrics` del server y muestreo local; SPEC-006 §4 intacto) |
| Tests | TEST-0160 (`AndroidSettingsStoreTest`): default true de la nueva preferencia + persistencia local sin tocar `video_config` |
| Docs | `testing/traceability.md` (fila TEST-0160, fila CR-079, Next = TEST-0161); `_index.md` de CRs (Next = CR-080) |

## 5. Testing evidence

- Suite JVM de la app + ktlint + detekt verdes tras el cambio
  (`./gradlew :app:testDebugUnitTest :app:ktlintMainSourceSetCheck :app:detekt`).
- Banco físico (flujo real de sesión) queda por verificación del owner:
  al entrar en sesión la hoja nace abierta sobre el video, se pliega al
  arrastrar abajo (quedando solo el asa visible, v4) y reabre al
  arrastrar arriba; con el toggle OFF no hay hoja.
- v4 (2026-11-05, tras la prueba del owner): asa arriba + tope de
  plegado corregido (la fórmula del v3 desalineaba la altura del asa y
  con el asa abajo el clamp impedía el plegado); gates + build verdes
  (`BUILD SUCCESSFUL`, exit 0) y APK reinstalado. La verificación del
  drag real vuelve al owner (requiere re-emparejar tras la
  reinstalación).
- v5 (2026-11-05, segunda prueba del owner): interacción full-height
  por gesto con decisión del 50 %; la asa arriba de la retícula (v4)
  queda intacta. Gates + build verdes (`BUILD SUCCESSFUL`, exit 0) y
  APK reinstalado; el barrido real vuelve a verificarse por el owner
  (re-emparejar tras la reinstalación).
- v6 (2026-11-05, tras los logs del banco): sin snap al soltar (queda
  en donde lo sueltas) + plegado inicial pos-medida; asa arriba
  intacta. Gates + build verdes (`BUILD SUCCESSFUL`, exit 0) y APK
  reinstalado; el barrido real lo verifica de nuevo el owner
  (re-emparejar tras la reinstalación).
- v7 (2026-11-05, tercera prueba): contenedor interceptor del drag +
  DOWN virtual; gates + build verdes (`BUILD SUCCESSFUL`, exit 0) y APK
  reinstalado. La interrupción del stream (lado server) queda por
  diagnóstico con captura en vivo en el próximo intento.
- v8 (2026-11-05, cuarta prueba del owner): franja del asa transparente
  (fondo Charcoal SOLO en `metrics_content`) + gate táctil por área
  visible de la hoja con helper puro y test JVM TEST-0172 (Robolectric);
  test enfocado verde primero y luego gate completo (`BUILD
  SUCCESSFUL`, exit 0) y APK reinstalada. La verificación táctil en
  banco (toques del juego libres con el panel plegado, drag solo desde
  el área visible) vuelve al owner (re-emparejar tras la
  reinstalación).
- v9 (2026-11-05, quinta prueba del owner): ajuste automático al final
  del recorrido — al 90 % la franja del asa se completa con fondo y el
  panel ajusta SOLO al 100 % (`ajusteAutomaticoAlFinal` +
  `franjaDelAsaConFondo`, helpers puros TEST-0173, constantes
  `FRACCION_AJUSTE_PANEL = 0.1`); test enfocado verde, luego gate
  completo (`BUILD SUCCESSFUL`, exit 0) y APK reinstalada. La
  verificación del ajuste en banco (drag cerca del tope → hoja completa
  sin franja transparente; bajar por debajo del 90 % → franja
  transparente de nuevo) vuelve al owner.
- v10 (2026-11-05, sexta prueba del owner): regla espejo del ajuste en
  el sentido inverso — al cerrar (drag hacia abajo), cuando al panel le
  queda 10 % del recorrido de plegado o menos (traslación ≥ 90 % del
  tope) se ajusta SOLO a la posición inicial (plegado completo, solo el
  asa); helper puro `pliegueAutomaticoAlFinal` aplicado en el MOVE
  (encadenado tras el ajuste de apertura), TEST-0174; test enfocado
  verde, luego gate completo (`BUILD SUCCESSFUL`, exit 0) y APK
  reinstalada. La verificación del ajuste en banco (drag de cierre cerca
  del plegado → la hoja se completa sola hasta la posición  inicial; subir
  de nuevo por encima del umbral → vuelve a quedar donde se suelta)
  vuelve al owner.
- v11 (2026-11-05, séptima prueba del owner): el umbral del ajuste
  automático sube de 10 % a 30 % en AMBOS sentidos (`FRACCION_AJUSTE_PANEL`:
  `0.1` → `0.3`), sin cambios de mecánica (mismo MOVE encadenado y
  misma condición de franja); TEST-0173/TEST-0174 actualizadas al
  nuevo umbral y verdes; gate completo (`BUILD SUCCESSFUL`, exit 0) y
  APK reinstalada. Verificación en banco (el snap al 100 % y el
  plegado completo deben verse desde el 70 % del recorrido) vuelve
  al owner.
- v12 (2026-11-05, octava prueba del owner): animación suave — plegado
  inicial animado (`0 → tope`, 150 ms) y en el drag SOLO el snap del
  ajuste automático anima (helper puro `panelConAnimacion`, TEST-0179);
  el drag a mano sigue pegado al dedo (v6 intacta). Asimismo la fila
  «Mostrar slider de métricas» sale del bloque Debug y pasa a la
  sección HUD (segunda fila, tras «Mostrar FPS»). Test enfocado verde,
  luego gate completo (`testDebugUnitTest` + ktlint + detekt +
  `assembleDebug` BUILD SUCCESSFUL, exit 0) y APK reinstalada. La
  verificación visual del suavizado en banco vuelve al owner.
- v13 (2026-11-06, CR-089): el ajuste automático deja de ser abrupto y su
  corte pasa de 30 % a 49 %/51 % (`FRACCION_AJUSTE_PANEL = 0.49`). Durante
  el arrastre el panel sigue al dedo 1:1 y el ajuste se decide una sola vez
  al soltar (`objetivoAlSoltar`): pasado el medio termina de abrirse, por
  debajo termina de plegarse; la animación es única, proporcional al
  recorrido real (140–280 ms) y con `DecelerateInterpolator` (antes 150 ms
  fijos y relanzados en cada MOVE), igual que el plegado inicial. La regla
  v6 se mantiene en la franja del medio; TEST-0184 y TEST-0173/TEST-0174
  al corte nuevo.

## 6. SPEC impact

- SPEC-002 §6: la fila del drawer es otra ahora (texto y semántica); el
  SPEC quedará por actualizar (patrón nota vigente, sin renumerar).
- El resto del contrato (CR-076 v3) no cambia: la vista frontal de
  espera sigue cubriendo INACTIVE/INTERRUPTED.
