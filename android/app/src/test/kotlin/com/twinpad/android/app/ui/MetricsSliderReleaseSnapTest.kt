package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0184 — suavizado del slider de métricas (CR-089): el ajuste
 * automático (corte en el medio, 49 %/51 %) se decide UNA vez al soltar el
 * dedo, no en cada MOVE — así el arrastre completo sigue al dedo y la
 * animación no se relanza a mitad de gesto (el salto al umbral era la
 * brusquedad reportada; con el corte en el medio, además, clavarlo durante
 * el arrastre dejaba el panel soldado) — y su duración es proporcional al
 * recorrido pendiente (140–280 ms con desaceleración) en vez de 150 ms
 * fijos con aceleración inicial.
 */
class MetricsSliderReleaseSnapTest {
    /** Tope de plegado del fixture (px). */
    private val tope = 1000f

    @Test
    fun pasado_el_medio_al_soltar_termina_de_abrir() {
        // Resto ≤ 49 % del tope (recorrido ≥ 51 %) → se completa al 100 %.
        assertEquals(0f, objetivoAlSoltar(490f, tope))
        assertEquals(0f, objetivoAlSoltar(300f, tope))
        assertEquals(0f, objetivoAlSoltar(0f, tope))
    }

    @Test
    fun por_debajo_del_medio_al_soltar_termina_de_plegar() {
        // Recorrido ≥ 51 % del tope → se completa el plegado (solo el asa).
        assertEquals(tope, objetivoAlSoltar(511f, tope))
        assertEquals(tope, objetivoAlSoltar(700f, tope))
        assertEquals(tope, objetivoAlSoltar(tope, tope))
    }

    @Test
    fun en_la_franja_del_medio_queda_donde_lo_sueltas() {
        // Entre 49 % y 51 % el corte no decide: se mantiene la regla v6.
        assertEquals(491f, objetivoAlSoltar(491f, tope))
        assertEquals(500f, objetivoAlSoltar(500f, tope))
        assertEquals(509f, objetivoAlSoltar(509f, tope))
    }

    @Test
    fun la_animacion_dura_en_proporcion_al_recorrido_pendiente() {
        assertEquals(ANIMACION_PANEL_MIN_MS, duracionAnimacionPanel(0f, tope))
        assertEquals(280L, duracionAnimacionPanel(tope, tope))
        assertEquals(210L, duracionAnimacionPanel(tope * 0.5f, tope))
    }

    @Test
    fun la_animacion_queda_acotada_entre_140_y_280_ms() {
        // Un recorrido mayor que el tope no alarga más allá del máximo.
        assertEquals(ANIMACION_PANEL_MAX_MS, duracionAnimacionPanel(tope * 5f, tope))
        // El signo del recorrido no importa (ajuste de apertura o de cierre).
        assertEquals(210L, duracionAnimacionPanel(-tope * 0.5f, tope))
        // Sin tope medido se usa el mínimo (no hay recorrido de referencia).
        assertEquals(ANIMACION_PANEL_MIN_MS, duracionAnimacionPanel(300f, 0f))
    }
}
