package com.twinpad.android.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twinpad.android.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-0172 — gate táctil del contenedor del slider (CR-079 v8, prueba del
 * owner): con el panel PLEGADO sobre el stream, un DOWN FUERA del área
 * visible de la hoja NO lo consume el slider (el dispatch lo entrega a la
 * superficie del juego) y el gesto del slider solo arranca DENTRO del área
 * visible — la franja del asa al estar plegado, con el tope subiendo a
 * medida que aparece el contenido. Fixture: contenedor 1000×2000 px con la
 * hoja plegada (traslación 500 = franja visible de 1500 px). Antes, el
 * contenedor fullscreen con elevación se comía CUALQUIER DOWN (el
 * controlador respondía `true` a todo) y arrastraba el panel con toques
 * sobre el juego.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MetricsSliderTouchGateTest {
    /** Estado del contenedor del fixture (px). */
    private val width = 1000
    private val height = 2000

    /** Traslación del panel plegado: franja visible = asa (1500 px). */
    private val foldTranslation = 500

    /** Contador de invocaciones del [MetricsSliderContainer.dragResponder]. */
    private var calls = 0

    /** Contenedor medido + plegado (traslación [foldTranslation]) con
     * dragResponder que cuenta y consume. */
    private fun containerPlegado(): MetricsSliderContainer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = MetricsSliderContainer(context)
        val sheet = FrameLayout(context)
        sheet.id = R.id.metrics_sheet
        val handle = View(context)
        sheet.addView(
            handle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            sheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, width, height)
        sheet.translationY = foldTranslation.toFloat()
        container.dragResponder =
            { _ ->
                calls++
                true
            }
        return container
    }

    private fun down(
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0,
        )

    private fun move(
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_MOVE,
            x,
            y,
            0,
        )

    @Test
    fun down_sobre_el_juego_con_panel_plegado_no_se_consume() {
        val container = containerPlegado()
        // y = 200 queda ARRIBA de la franja visible (traslación 500):
        // es la superficie del juego, no el slider.
        assertFalse(container.onTouchEvent(down(100f, 200f)))
        assertEquals(0, calls)
    }

    @Test
    fun down_en_la_franja_visible_arranca_el_drag() {
        val container = containerPlegado()
        // y = 600 cae DENTRO de la franja visible del panel plegado.
        assertTrue(container.onTouchEvent(down(100f, 600f)))
        assertEquals(1, calls)
    }

    @Test
    fun move_tras_down_valido_sigue_al_drag_responder() {
        val container = containerPlegado()
        assertTrue(container.onTouchEvent(down(100f, 600f)))
        // Continuidad CR-082 intacta: los MOVE del gesto activo llegan al
        // controlador sin gate (el DOWN ya validó la zona).
        assertTrue(container.onTouchEvent(move(100f, 640f)))
        assertEquals(2, calls)
    }

    @Test
    fun down_en_hoja_desplegada_no_se_pierde() {
        val container = containerPlegado()
        // Hoja desplegada (traslación 0): todo el contenedor es área visible.
        sheetTranslationY(container)
        assertTrue(container.onTouchEvent(down(100f, 200f)))
        assertEquals(1, calls)
    }

    /** Traslación de la hoja del fixture a 0 (panel desplegado). */
    private fun sheetTranslationY(container: MetricsSliderContainer) {
        container.findViewById<FrameLayout>(R.id.metrics_sheet).translationY = 0f
    }

    @Test
    fun helper_puro_del_gate_por_borde_visible() {
        // El punto pertenece al área visible cuando alcanza el tope visible.
        assertFalse(enHojaVisible(200f, 500f))
        assertTrue(enHojaVisible(500f, 500f))
        assertTrue(enHojaVisible(700f, 500f))
    }
}
