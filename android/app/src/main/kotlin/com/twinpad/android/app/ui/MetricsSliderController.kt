package com.twinpad.android.app.ui

import android.app.Activity
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.twinpad.android.app.R

/** CR-079 (v5): elevación de la hoja sobre el stream (sobre
 * surface_container; sin esto la superficie táctil del juego roba el drag). */
private const val SHEET_ELEVATION_DP = 8f

/** CR-079 (v9, prueba del owner; v11 sube el umbral de 10 % a 30 %):
 * cuando le queda al panel 30 % del tope de plegado o menos (recorrido
 * ≥ 70 %), se ajusta SOLO al 100 % y la franja del asa deja de ser
 * transparente. */
internal const val FRACCION_AJUSTE_PANEL = 0.3f

/** CR-079 (v9, umbral de v11): traslación ajustada del panel — con resto
 * ≤ 30 % del tope de plegado el ajuste automático lo lleva al 100 %
 * (traslación 0); como el slider lateral (v6), debajo queda donde
 * lo sueltas. */
internal fun ajusteAutomaticoAlFinal(
    target: Float,
    topePlegado: Float,
): Float = if (target <= topePlegado * FRACCION_AJUSTE_PANEL) 0f else target

/** CR-079 (v10, prueba del owner; v11 sube el umbral de 10 % a 30 %):
 * espejo del ajuste de apertura — al cerrar (drag hacia abajo), cuando
 * al panel le queda 30 % del recorrido de plegado o menos (traslación
 * ≥ 70 % del tope) se ajusta SOLO a la posición inicial (plegado
 * completo, solo el asa); por encima sigue la regla v6 (queda donde lo
 * sueltas). */
internal fun pliegueAutomaticoAlFinal(
    target: Float,
    topePlegado: Float,
): Float = if (target >= topePlegado * (1f - FRACCION_AJUSTE_PANEL)) topePlegado else target

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
 * plegado con solo el asa visible pegada al borde inferior). Al soltar el
 * panel QUEDA EN DONDE LO SUELTAS (comportamiento del slider lateral que
 * el owner pidió: «en resumen debe funcionar como el slider lateral pero
 * en la parte inferior de arriba a abajo»), sin decisión por umbral. Nace
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

    /** CR-079 v8: región con fondo Charcoal (retícula + txt_games); la
     * franja del asa se queda sin fondo para ver solo el drag. */
    private val content: ViewGroup = activity.findViewById(R.id.metrics_content)
    private val handle: View = activity.findViewById(R.id.metrics_handle)

    private var mode = SheetMode.HIDDEN
    private var downRawY = 0f
    private var translationAtDown = 0f
    private var plegadoPendiente = false

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
                sheet.translationY = peekTranslation().toFloat()
                aplicarFondoFranja(sheet.translationY)
                plegadoPendiente = false
            } else {
                aplicarPlegadoCuandoMedido()
            }
        }
    }

    /** CR-079 v9: la franja del asa deja de ser transparente cuando el
     * panel queda cerca del 100 % (recorrido ≥ 70 %, umbral v11); con la
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
                // CR-079 v9 (umbral v11): con resto ≤ 30 % del plegado
                // (recorrido ≥ 70 %) el panel se ajusta SOLO al 100 %
                // (traslación 0) y la franja del asa deja de ser transparente.
                // CR-079 v10 (umbral v11): en el sentido inverso (cierre),
                // cuando el panel lleva recorrido ≥ 70 % del plegado se
                // ajusta SOLO a la posición inicial (plegado completo).
                val target =
                    pliegueAutomaticoAlFinal(
                        ajusteAutomaticoAlFinal(
                            (translationAtDown + dy).coerceIn(0f, tope),
                            tope,
                        ),
                        tope,
                    )
                sheet.translationY = target
                aplicarFondoFranja(target)
                Log.d(TAG_LOG, "drag dy=$dy -> t=$target (tope=$tope)")
            }
            // v6: sin decisión al soltar (slider lateral): el panel queda
            // en la traslación exacta del soltar; el dedo manda.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                downCapturado = false
                Log.d(TAG_LOG, "drag soltado t=${sheet.translationY} (queda ahi)")
            }
        }
        return true
    }

    private companion object {
        /** Tag temporal del diagnóstico del drag (CR-079, se retira luego). */
        const val TAG_LOG = "MetricsSheet"
    }
}
