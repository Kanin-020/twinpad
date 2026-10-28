package com.twinpad.android.app.surface

// TEST-090 — CR-032: distribución de botones de cara del perfil táctil
// (A derecha, B abajo, X arriba, Y izquierda con Nintendo; A abajo, B
// derecha, X izquierda, Y arriba con Xbox/Play) — SPEC-008 §11 R5.
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceDistributionTest {
    private val baseline = ControlProfileCodec.defaultProfile()

    private fun controlWith(
        hid: String,
        profile: ControlProfile,
    ): TouchControl? = profile.controls.first { it.hid == hid && it.kind == ControlProfileCodec.KIND_BUTTON }

    private fun position(hid: String): Pair<Float, Float> {
        val control = controlWith(hid, baseline)
        return Pair(control!!.x, control.y)
    }

    @Test
    fun `default sigue la distribucion xbox play`() {
        // A abajo, B derecha, X izquierda, Y arriba (SPEC-008 §10).
        val expected =
            listOf(
                "button_a" to (0.82f to 0.49f),
                "button_b" to (0.90f to 0.39f),
                "button_x" to (0.74f to 0.39f),
                "button_y" to (0.84f to 0.29f),
            )
        expected.forEach { (hid, xy) -> assertEquals(xy, position(hid)) }
    }

    @Test
    fun `distribucion nintendo recoloca solo los cuatro de cara`() {
        val nintendo = applyFaceDistribution(baseline, nintendo = true)
        // A derecha, B abajo, X arriba, Y izquierda (CR-032).
        val expected =
            listOf(
                "button_a" to (0.90f to 0.39f),
                "button_b" to (0.82f to 0.49f),
                "button_x" to (0.84f to 0.29f),
                "button_y" to (0.74f to 0.39f),
            )
        expected.forEach { (hid, xy) ->
            val control = controlWith(hid, nintendo)!!
            assertEquals(xy.first, control.x, EPS)
            assertEquals(xy.second, control.y, EPS)
        }
        // El resto del perfil queda intacto: D-pad, sticks y botones de
        // hombro/triggers conservan id, label, hid y posición.
        val defaultDpad = baseline.controls.first { it.kind == ControlProfileCodec.KIND_DPAD }
        val nintendoDpad = nintendo.controls.first { it.kind == ControlProfileCodec.KIND_DPAD }
        assertEquals(defaultDpad, nintendoDpad)
        assertEquals(baseline.controls.size, nintendo.controls.size)
        val l1 = controlWith("button_l1", nintendo)!!
        assertEquals("L1", l1.label)
    }

    @Test
    fun `apagar vuelve a las posiciones canonicas y es idempotente`() {
        val nintendo = applyFaceDistribution(baseline, nintendo = true)
        val restored = applyFaceDistribution(nintendo, nintendo = false)
        val again = applyFaceDistribution(restored, nintendo = true)
        assertEquals(baseline.controls, restored.controls)
        assertEquals(nintendo.controls, again.controls)
    }

    @Test
    fun `etiqueta y asignacion viajan con el control`() {
        val nintendo = applyFaceDistribution(baseline, nintendo = true)
        val a = controlWith("button_a", nintendo)!!
        assertEquals("A", a.label)
        assertEquals("button_a", a.hid)
        val y = controlWith("button_y", nintendo)!!
        assertEquals("Y", y.label)
        assertEquals("button_y", y.hid)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
