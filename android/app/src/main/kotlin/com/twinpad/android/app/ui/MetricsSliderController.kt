package com.twinpad.android.app.ui

import android.app.Activity
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.twinpad.android.app.R
import kotlin.math.abs

/** CR-079 (v5): elevación de la hoja sobre el stream (sobre
 * surface_container; sin esto la superficie táctil del juego roba el drag). */
private const val SHEET_ELEVATION_DP = 8f

/** CR-079 (v9, prueba del owner; v11 sube el umbral de 10 % a 30 % y
 * CR-089 al 49 % con el ajuste AL SOLTAR): cuando le queda al panel 49 %
 * del tope de plegado o menos (recorrido ≥ 51 %, el panel ya pasó del
 * medio), al soltar el dedo se ajusta SOLO al 100 % y la franja del asa
 * deja de ser transparente. */
internal const val FRACCION_AJUSTE_PANEL = 0.49f

/** CR-079 (v9, umbral de v11 y CR-089): traslación ajustada del panel — con
 * resto ≤ 49 % del tope de plegado el ajuste automático lo lleva al 100 %
 * (traslación 0); como el slider lateral (v6), debajo queda donde
 * lo sueltas. */
internal fun ajusteAutomaticoAlFinal(
    target: Float,
    topePlegado: Float,
): Float = if (target <= topePlegado * FRACCION_AJUSTE_PANEL) 0f else target

/** CR-079 (v10, prueba del owner; v11 sube el umbral de 10 % a 30 % y
 * CR-089 al 51 % del tope): espejo del ajuste de apertura — al cerrar
 * (drag hacia abajo), cuando al panel le queda 49 % del recorrido de
 * plegado o menos (traslación ≥ 51 % del tope) se ajusta SOLO a la
 * posición inicial (plegado completo, solo el asa); por encima sigue la
 * regla v6 (queda donde lo sueltas). */
internal fun pliegueAutomaticoAlFinal(
    target: Float,
    topePlegado: Float,
): Float = if (target >= topePlegado * (1f - FRACCION_AJUSTE_PANEL)) topePlegado else target

/** CR-089: duración del snap y del plegado inicial — proporcional al
 * recorrido que falta (un salto del 30 % del tope no se anima igual que el
 * plegado completo) y siempre con desaceleración al llegar. */
internal const val ANIMACION_PANEL_MIN_MS = 140L
internal const val ANIMACION_PANEL_MAX_MS = 280L

/** CR-089 (v2): objetivo del panel al soltar el dedo — el ajuste automático
 * ya NO se aplica durante el arrastre (con el corte en el medio, clavarlo
 * mientras el dedo sigue dejaba el panel soldado y sin poder invertir el
 * gesto). Al soltar, el corte del medio decide de una vez: pasado el medio
 * termina de abrirse (0), por debajo termina de plegarse ([tope]) y en la
 * franja intermedia se queda donde lo sueltas (v6). */
internal fun objetivoAlSoltar(
    posicion: Float,
    topePlegado: Float,
): Float = pliegueAutomaticoAlFinal(ajusteAutomaticoAlFinal(posicion, topePlegado), topePlegado)

/** CR-089: duración del snap según el recorrido pendiente (0…tope) →
 * 140…280 ms; `tope` inválido usa el mínimo. */
internal fun duracionAnimacionPanel(
    distancia: Float,
    tope: Float,
): Long {
    if (tope <= 0f) return ANIMACION_PANEL_MIN_MS
    val fraccion = (abs(distancia) / tope).coerceIn(0f, 1f)
    val extra = (ANIMACION_PANEL_MAX_MS - ANIMACION_PANEL_MIN_MS) * fraccion
    return (ANIMACION_PANEL_MIN_MS + extra).toLong()
}

/** CR-079 (v12): SOLO el snap del ajuste automático se anima suave — el
 * drag a mano sigue pegado al dedo sin retardo (regla v6 intacta) y solo
 * cuando el ajuste cambia el valor bruto por el objetivo snap anima. */
internal fun panelConAnimacion(
    bruto: Float,
    target: Float,
): Boolean = target != bruto

/** CR-079 (v9, umbral de v11): la franja del asa lleva fondo SOLO cuando
 * el panel queda cercano/sobre el 100 % (misma condición del ajuste
 * automático); por debajo, transparente (se ve únicamente el asa,
 * regla v8). */
internal fun franjaDelAsaConFondo(
    traslacion: Float,
    topePlegado: Float,
): Boolean = traslacion <= topePlegado * FRACCION_AJUSTE_PANEL

/**
 * CR-079 (v6 tras la segunda prueba del owner): los gauges del CR-076 son LA MISMA
 * retícula en dos presentaciones. Sin stream ocupa la pantalla completa como
 * vista de espera (fondo Void Canvas, regla CR-076 v3 sin cambios); en
 * STREAMING con «Mostrar slider de métricas» vive como panel full-height que
 * SIGUE AL DEDO en los dos extremos del barrido: arrastrando desde la parte
 * baja hasta la parte superior de la pantalla queda en PANTALLA COMPLETA, y
 * arrastrando desde la parte superior hacia la inferior se quita (queda
 * plegado con solo el asa visible pegada al borde inferior). Mientras se
 * arrastra el panel sigue al dedo (comportamiento del slider lateral que
 * el owner pidió: «en resumen debe funcionar como el slider lateral pero
 * en la parte inferior de arriba a abajo»); al soltar, si el dedo no pasó
 * del medio queda donde lo sueltas y si lo pasó una sola animación termina
 * de abrirlo o plegarlo (CR-089, corte 49 %/51 %). Nace
 * PLEGADO (solo el asa, decisión derivada del ciclo de gestos que el
 * owner describió: el slider se pone en pantalla completa ARRASTRANDO, no
 * de entrada). Con el
 * ajuste desmarcado no se ve NI el asa NI el panel. El drag lo resuelven el
 * asa Y el panel; la elevación en runtime lo pone sobre surface_container
 * como el HUD y el botón de ajustes. El pintado sigue en [WaitingGaugePanel];
 * esta clase solo posiciona, no pinta ni muestrea.
 *
 * Logs temporales de diagnóstico con tag [TAG_LOG] (quitar al cerrar el CR).
 */
internal class MetricsSliderController(
    private val activity: Activity,
) {
    private enum class SheetMode { WAITING, SHEET, HIDDEN }

    private val slider: MetricsSliderContainer =
        activity.findViewById(R.id.metrics_slider)
    private val sheet: ViewGroup = activity.findViewById(R.id.metrics_sheet)

    /** CR-079 v8: región con fondo Charcoal (la retícula); la
     * franja del asa se queda sin fondo para ver solo el drag. */
    private val content: ViewGroup = activity.findViewById(R.id.metrics_content)
    private val handle: View = activity.findViewById(R.id.metrics_handle)

    private var mode = SheetMode.HIDDEN
    private var downRawY = 0f
    private var translationAtDown = 0f
    private var plegadoPendiente = false

    private val desaceleracion = DecelerateInterpolator()

    /** `true` si el DOWN ya capturó el estado del gesto (del asa, la hoja
     * o el intercept del contenedor). */
    private var downCapturado = false

    /** `true` en STREAMING con el slider activo: el tick pinta los gauges. */
    val sheetActive: Boolean
        get() = mode == SheetMode.SHEET

    private val dragListener = View.OnTouchListener { _, event -> onTouch(event) }

    init {
        sheet.setOnTouchListener(dragListener)
        // La asa lleva el MISMO oyente: es la única zona visible con el panel
        // plegado y responde arrastrada por su área exacta.
        handle.setOnTouchListener(dragListener)
        // v7: el contenedor intercepta los drags verticales de la hoja y los
        // delega aquí; el drag deja de depender de quién tomó el DOWN.
        slider.dragResponder = { event -> onTouch(event) }
    }

    /** Idempotente: solo re-aplica cuando cambia el estado. */
    fun setStreaming(
        streaming: Boolean,
        sliderEnabled: Boolean,
    ) {
        val target =
            when {
                !streaming -> SheetMode.WAITING
                sliderEnabled -> SheetMode.SHEET
                else -> SheetMode.HIDDEN
            }
        if (target == mode) {
            return
        }
        mode = target
        applyCurrent()
    }

    private fun applyCurrent() {
        when (mode) {
            SheetMode.WAITING -> {
                slider.isVisible = true
                slider.elevation = 0f
                sheet.layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                sheet.setBackgroundResource(R.color.design_void_canvas)
                content.background = null
                handle.isVisible = false
                sheet.translationY = 0f
                sheet.requestLayout()
            }
            SheetMode.SHEET -> {
                slider.isVisible = true
                val density = activity.resources.displayMetrics.density
                slider.elevation = SHEET_ELEVATION_DP * density
                sheet.layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.BOTTOM,
                    )
                // v8: el fondo Charcoal vive SOLO en el contenido; la franja
                // del asa queda transparente (plegado se ve únicamente el asa).
                sheet.background = null
                content.background = ContextCompat.getDrawable(activity, R.drawable.bg_metrics_sheet)
                handle.isVisible = true
                sheet.requestLayout()
                // Nace PLEGADO: la traslación se aplica cuando el contenedor
                // y el asa ya estén medidos (post temprano = nacer abierto).
                plegadoPendiente = true
                aplicarPlegadoCuandoMedido()
            }
            SheetMode.HIDDEN -> slider.isVisible = false
        }
    }

    /** Aplica la traslación de plegado inicial una vez medida la vista. */
    private fun aplicarPlegadoCuandoMedido() {
        slider.post {
            if (mode != SheetMode.SHEET || !plegadoPendiente) {
                return@post
            }
            if (slider.height > 0 && handle.height > 0) {
                // CR-079 v12: el plegado inicial ANIMA en vez de saltar —
                // la hoja nace en 0 y desliza suave hasta el asa.
                val tope = peekTranslation().toFloat()
                // CR-089: el plegado inicial dura según el recorrido y llega
                // desacelerando (antes: 150 ms fijos con aceleración inicial).
                sheet.animate()
                    .translationY(tope)
                    .setDuration(duracionAnimacionPanel(tope, tope))
                    .setInterpolator(desaceleracion)
                    .start()
                aplicarFondoFranja(tope)
                plegadoPendiente = false
            } else {
                aplicarPlegadoCuandoMedido()
            }
        }
    }

    /** CR-079 v12: traslación del panel — el drag a mano queda pegado al
     * dedo (sin animación) y SOLO el ajuste desliza suave. CR-089 (v2): ese
     * ajuste se lanza una sola vez, al soltar el dedo, así que el arrastre
     * completo es 1:1 y la animación no se relanza nunca a mitad de gesto. */
    private fun aplicarTraslacion(
        bruto: Float,
        target: Float,
    ) {
        val animator = sheet.animate()
        if (!panelConAnimacion(bruto, target)) {
            animator.cancel()
            sheet.translationY = target
            return
        }
        // CR-089: la duración mide el recorrido REAL que le queda al panel
        // (posición viva, no el bruto del dedo) — un snap corto cerca del
        // umbral dura ~140 ms y el plegado completo ~280 ms.
        animator
            .translationY(target)
            .setDuration(duracionAnimacionPanel(target - sheet.translationY, peekTranslation().toFloat()))
            .setInterpolator(desaceleracion)
            .start()
    }

    /** CR-079 v9: la franja del asa deja de ser transparente cuando el
     * panel queda cerca del 100 % (recorrido ≥ 51 %, umbral CR-089); con la
     * franja pintada la hoja se ve completa. */
    private fun aplicarFondoFranja(traslacion: Float) {
        sheet.background =
            if (franjaDelAsaConFondo(traslacion, peekTranslation().toFloat())) {
                ContextCompat.getDrawable(activity, R.drawable.bg_metrics_sheet)
            } else {
                null
            }
    }

    /** Tope de plegado (v5): la hoja baja hasta que el asa (que vive arriba
     * de la retícula) queda totalmente visible pegada al borde inferior. */
    private fun peekTranslation(): Int {
        if (handle.height > 0) {
            return (sheet.height - (handle.top + handle.height)).coerceAtLeast(0)
        }
        return 0
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (mode != SheetMode.SHEET) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                translationAtDown = sheet.translationY
                downCapturado = true
                Log.d(TAG_LOG, "drag inicio yRaw=$downRawY tInicial=$translationAtDown")
            }
            MotionEvent.ACTION_MOVE -> {
                if (!downCapturado) {
                    // DOWN virtual: el intercept del contenedor arranca el
                    // gesto sin DOWN propio (el desvío del slop es mínimo).
                    downRawY = event.rawY
                    translationAtDown = sheet.translationY
                    downCapturado = true
                }
                val dy = event.rawY - downRawY
                val tope = peekTranslation().toFloat()
                // CR-089 (v2): durante el arrastre el panel sigue al dedo 1:1,
                // sin ajustes ni animaciones — el salto al cruzar el umbral era
                // la brusquedad que reportó el owner, y con el corte en el
                // medio cualquier MOVE ya caía en un tramo de ajuste (el panel
                // se quedaba soldado y no se podía invertir el gesto). El
                // ajuste se decide UNA vez, al soltar ([objetivoAlSoltar]).
                val bruto = (translationAtDown + dy).coerceIn(0f, tope)
                aplicarTraslacion(bruto, bruto)
                aplicarFondoFranja(bruto)
                Log.d(TAG_LOG, "drag dy=$dy -> t=$bruto (tope=$tope)")
            }
            // CR-089 (v2) sobre la regla v6: al soltar sin cruzar el medio el
            // panel queda en la traslación exacta del soltar; cruzado el
            // medio, una sola animación termina de abrirlo o plegarlo.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                downCapturado = false
                val tope = peekTranslation().toFloat()
                val soltado = sheet.translationY.coerceIn(0f, tope)
                val objetivo = objetivoAlSoltar(soltado, tope)
                if (objetivo != soltado) {
                    aplicarTraslacion(soltado, objetivo)
                    aplicarFondoFranja(objetivo)
                }
                Log.d(TAG_LOG, "drag soltado t=$soltado -> objetivo=$objetivo (tope=$tope)")
            }
        }
        return true
    }

    private companion object {
        /** Tag temporal del diagnóstico del drag (CR-079, se retira luego). */
        const val TAG_LOG = "MetricsSheet"
    }
}
