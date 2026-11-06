package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0173 — ajuste automático al final del recorrido (CR-079 v9,
 * umbral de v11 y CR-089, prueba del owner): cuando le queda al panel
 * 49 % del tope de plegado o menos (recorrido ≥ 51 % de la apertura, ya
 * pasado el medio), se ajusta SOLO al 100 % (traslación 0) y la franja
 * del asa deja de ser transparente (fondo Charcoal también en la franja);
 * por debajo del umbral queda donde lo sueltas (v6) y la franja vuelve a
 * ser transparente (v8: se ve únicamente el asa).
 */
class MetricsSliderFinalAdjustTest {
    /** Tope de plegado del fixture (px). */
    private val tope = 1000f

    @Test
    fun con_resto_de_49_por_ciento_o_menos_se_ajusta_al_100() {
        // Resto ≤ 49 % del tope (traslación ≤ 0.49 × 1000) → snap al 100 %.
        assertEquals(0f, ajusteAutomaticoAlFinal(490f, tope))
        assertEquals(0f, ajusteAutomaticoAlFinal(300f, tope))
        assertEquals(0f, ajusteAutomaticoAlFinal(0f, tope))
    }

    @Test
    fun por_debajo_del_umbral_queda_donde_lo_sueltas() {
        // La regla v6 (slider lateral) sigue intacta fuera del tramo final.
        assertEquals(491f, ajusteAutomaticoAlFinal(491f, tope))
        assertEquals(700f, ajusteAutomaticoAlFinal(700f, tope))
        assertEquals(tope, ajusteAutomaticoAlFinal(tope, tope))
    }

    @Test
    fun franja_con_fondo_solo_en_el_tramo_final() {
        // Plegado completo y por debajo del umbral → transparente (v8).
        assertFalse(franjaDelAsaConFondo(tope, tope))
        assertFalse(franjaDelAsaConFondo(700f, tope))
        assertFalse(franjaDelAsaConFondo(491f, tope))
        // Dentro del tramo final (resto ≤ 49 %) → franja completada.
        assertTrue(franjaDelAsaConFondo(490f, tope))
        assertTrue(franjaDelAsaConFondo(0f, tope))
    }

    @Test
    fun umbral_exacto_del_owner() {
        assertEquals(0.49f, FRACCION_AJUSTE_PANEL)
        // El propio umbral (resto = 49 % justo) YA ajusta al 100 %.
        assertEquals(0f, ajusteAutomaticoAlFinal(tope * FRACCION_AJUSTE_PANEL, tope))
    }
}
