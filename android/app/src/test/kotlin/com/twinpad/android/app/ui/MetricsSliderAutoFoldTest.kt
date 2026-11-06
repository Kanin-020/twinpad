package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0174 — ajuste automático al final del cierre (CR-079 v10,
 * umbral de v11, prueba del owner): en el sentido inverso, cuando el
 * panel se está quitando y le queda 30 % del recorrido de plegado o
 * menos (traslación ≥ 70 % del tope), se ajusta SOLO a la posición
 * inicial (plegado completo, solo el asa); por encima sigue la regla v6
 * (queda donde lo sueltas).
 */
class MetricsSliderAutoFoldTest {
    /** Tope de plegado del fixture (px). */
    private val tope = 1000f

    @Test
    fun con_resto_de_30_por_ciento_o_menos_se_ajusta_a_la_posicion_inicial() {
        // Recorrido de plegado ≥ 70 % (resto ≤ 30 %) → plegado completo.
        assertEquals(tope, pliegueAutomaticoAlFinal(tope * 0.95f, tope))
        assertEquals(tope, pliegueAutomaticoAlFinal(tope * 0.7f, tope))
        assertEquals(tope, pliegueAutomaticoAlFinal(tope, tope))
    }

    @Test
    fun por_debajo_del_70_del_cierre_queda_donde_lo_sueltas() {
        // La regla v6 (slider lateral) sigue intacta fuera del tramo final.
        assertEquals(50f, pliegueAutomaticoAlFinal(50f, tope))
        assertEquals(500f, pliegueAutomaticoAlFinal(500f, tope))
        assertEquals(699f, pliegueAutomaticoAlFinal(699f, tope))
    }
}
