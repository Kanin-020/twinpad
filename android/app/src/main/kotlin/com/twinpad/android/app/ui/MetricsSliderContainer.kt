package com.twinpad.android.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.twinpad.android.app.R

/**
 * CR-079 (v7): contenedor del slider de métricas. Además del orden
 * declarado en el XML, intercepta los deslizamientos verticales que
 * arrancan dentro de la hoja y los entrega al controlador: el drag deja de
 * depender de qué hijo consumió el DOWN (HUD, gauges); con el
 * panel PLEGADO el hit-test solo alcanza la franja visible (el asa), y los
 * toques del juego quedan intactos fuera del panel. Responde solo con el
 * [dragResponder] activo (modo hoja); sin él no consume nada.
 */
internal class MetricsSliderContainer(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    /** Delegación del gesto interceptado (devuelve `true` si lo maneja). */
    var dragResponder: ((MotionEvent) -> Boolean)? = null

    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    private val touchSlopPx: Int =
        ViewConfiguration.get(context).scaledTouchSlop

    /** CR-079 (v8, prueba del owner): sin este gate, un DOWN fuera de la
     * franja visible del panel (toque sobre el juego con el panel PLEGADO)
     * caía en [onTouchEvent] y se consumía SIEMPRE (el controlador
     * respondía `true` a todo el gesto): se interponía en los toques del
     * juego y hasta arrastraba el panel. El DOWN del gesto del slider que
     * no encuentra hijo solo es válido DENTRO del área visible de la
     * hoja: con el panel plegado esa área es la franja del asa; el tope
     * visible sube a medida que aparece el contenido. */
    private val sheet: View by lazy { findViewById<View>(R.id.metrics_sheet) }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                tracking = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val lejania = dx * dx + dy * dy
                    if (lejania > touchSlopPx * touchSlopPx && dy * dy > dx * dx) {
                        tracking = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return tracking && (dragResponder?.invoke(event) ?: false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // CR-082: continuidad del drag — tras el intercept los siguientes
        // eventos llegan a ESTE onTouchEvent (el hijo recibe cancel): el
        // mismo cálculo del drag continúa en el parent y el panel sigue al
        // dedo en todo el barrido (sin esto el gesto muere tras el slop y
        // los drags solo avanzan unos px, evidencia del owner 16:07).
        // CR-079 v8: un DOWN que no cayó en ningún hijo y cae FUERA del
        // área visible de la hoja no es del slider — se rechaza y el
        // dispatch del layout lo entrega a la superficie del juego.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val topVisible = hojaTopVisible()
            if (topVisible == null || !enHojaVisible(event.y, topVisible)) {
                return super.onTouchEvent(event)
            }
        }
        return dragResponder?.invoke(event) ?: super.onTouchEvent(event)
    }

    /** Tope visible de la hoja en coordenadas del contenedor: la hoja llena
     * el contenedor y su borde superior visible es la traslación vigente;
     * `null` si la hoja aún no se midió (no hay área visible → no hay
     * gesto del slider). */
    private fun hojaTopVisible(): Float? = if (sheet.height <= 0) null else sheet.translationY
}

/** CR-079 (v8): helper puro del gate táctil — un punto local pertenece al
 * área visible de la hoja cuando su eje Y alcanza el borde superior
 * visible de la misma (TEST-0172). */
internal fun enHojaVisible(
    yLocal: Float,
    topVisible: Float,
): Boolean = yLocal >= topVisible
