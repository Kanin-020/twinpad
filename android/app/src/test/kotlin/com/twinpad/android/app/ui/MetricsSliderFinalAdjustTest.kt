package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0173 — ajuste automático al final del recorrido (CR-079 v9,
 * umbral de v11, prueba del owner): cuando le queda al panel 30 % del
 * tope de plegado o menos (recorrido ≥ 70 % de la apertura), se ajusta
 * SOLO al 100 % (traslación 0) y la franja del asa deja de ser
 * transparente (fondo Charcoal también en la franja); por debajo del
 * umbral queda donde lo sueltas (v6) y la franja vuelve a ser
 * transparente (v8: se ve únicamente el asa).
 */
class MetricsSliderFinalAdjustTest {
    /** Tope de plegado del fixture (px). */
    private val tope = 1000f

    @Test
    fun con_resto_de_30_por_ciento_o_menos_se_ajusta_al_100() {
        // Resto ≤ 30 % del tope (traslación ≤ 0.3 × 1000) → snap al 100 %.
        assertEquals(0f, ajusteAutomaticoAlFinal(300f, tope))
        assertEquals(0f, ajusteAutomaticoAlFinal(50f, tope))
        assertEquals(0f, ajusteAutomaticoAlFinal(0f, tope))
    }

    @Test
    fun por_debajo_del_umbral_queda_donde_lo_sueltas() {
        // La regla v6 (slider lateral) sigue intacta fuera del tramo final.
        assertEquals(301f, ajusteAutomaticoAlFinal(301f, tope))
        assertEquals(500f, ajusteAutomaticoAlFinal(500f, tope))
        assertEquals(tope, ajusteAutomaticoAlFinal(tope, tope))
    }

    @Test
    fun franja_con_fondo_solo_en_el_tramo_final() {
        // Plegado completo y medio recorrido → transparente (v8).
        assertFalse(franjaDelAsaConFondo(tope, tope))
        assertFalse(franjaDelAsaConFondo(500f, tope))
        assertFalse(franjaDelAsaConFondo(301f, tope))
        // Dentro del tramo final (resto ≤ 30 %) → franja completada.
        assertTrue(franjaDelAsaConFondo(300f, tope))
        assertTrue(franjaDelAsaConFondo(0f, tope))
    }

    @Test
    fun umbral_exacto_del_owner() {
        assertEquals(0.3f, FRACCION_AJUSTE_PANEL)
        // El propio umbral (resto = 30 % justo) YA ajusta al 100 %.
        assertEquals(0f, ajusteAutomaticoAlFinal(tope * FRACCION_AJUSTE_PANEL, tope))
    }
}
