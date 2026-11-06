package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-0182 — verifies SPEC-002 R22 (CR-086 v2): el brillo de carga de los
 * anillos avanza sobre el arco lleno y solo se dibuja donde hay dato; mismas
 * entradas producen siempre el mismo tramo (función pura). */
class GaugeViewChargingTest {
    private val delta = 0.01f

    @Test
    fun highlight_sweeps_inside_the_filled_arc() {
        val span = chargingHighlightSpan(phase = 0.5f, filledSweep = 200f)!!
        assertEquals(144f, span.start, delta)
        assertEquals(180f, span.endInclusive, delta)
        assertEquals(36f, span.endInclusive - span.start, delta)
    }

    @Test
    fun highlight_clips_at_the_arc_start_and_never_before_it() {
        val span = chargingHighlightSpan(phase = 0.02f, filledSweep = 200f)!!
        assertEquals(0f, span.start, delta)
        assertEquals(7.2f, span.endInclusive, delta)
    }

    @Test
    fun highlight_is_hidden_when_it_does_not_overlap_the_filled_arc() {
        assertNull(chargingHighlightSpan(phase = 0.9f, filledSweep = 120f))
        assertNull(chargingHighlightSpan(phase = 0.5f, filledSweep = 0f))
    }

    @Test
    fun phase_is_clamped_and_full_arc_takes_the_last_span() {
        val span = chargingHighlightSpan(phase = 2f, filledSweep = 400f)!!
        assertEquals(324f, span.start, delta)
        assertEquals(360f, span.endInclusive, delta)
    }
}
