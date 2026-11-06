package com.twinpad.android.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0179 — suavizado de la animación del slider (CR-079 v12, prueba
 * del owner): SOLO el snap del ajuste automático se anima suave — el drag
 * a mano sigue pegado al dedo sin retardo (regla v6 intacta): el helper
 * puro señala animación únicamente cuando el objetivo SNAP difiere del
 * valor bruto del dedo.
 */
class MetricsSliderAnimationTest {
    @Test
    fun snap_del_ajuste_automatico_se_anima() {
        // Apertura: el dedo a 400 px pero el ajuste snap al 100 % (0) → anima.
        assertTrue(panelConAnimacion(400f, 0f))
        // Cierre: el dedo a 950 px pero el ajuste plega completo (1000) → anima.
        assertTrue(panelConAnimacion(950f, 1000f))
    }

    @Test
    fun drag_a_mano_queda_pegado_al_dedo_sin_animacion() {
        // Mismo valor bruto y objetivo: el dedo manda, sin animación (v6).
        assertFalse(panelConAnimacion(400f, 400f))
        assertFalse(panelConAnimacion(0f, 0f))
        assertFalse(panelConAnimacion(-0f, 0f))
    }
}
