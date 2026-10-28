package com.twinpad.android.app.hid

// TEST-087 — CR-028: mapper puro del mando físico conectado al teléfono y
// fusión con la superficie táctil (SPEC-004 §4: Signature sin cambios).
import com.twinpad.android.app.hid.PhysicalGamepadAlgo.KeyCodes
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class PhysicalGamepadAlgoTest {
    /** Fábrica compacta para mantener las líneas del test bajo el máximo;
     * espejo del evento de plataforma (los 8 parámetros existen en él). */
    @Suppress("LongParameterList")
    private fun motion(
        leftX: Float = 0f,
        leftY: Float = 0f,
        rightX: Float = 0f,
        rightY: Float = 0f,
        triggerL: Float = 0f,
        triggerR: Float = 0f,
        hatX: Int = 0,
        hatY: Int = 0,
    ): PhysicalMotion =
        PhysicalMotion(
            leftX,
            leftY,
            rightX,
            rightY,
            triggerL,
            triggerR,
            hatX,
            hatY,
        )

    private fun touchSnapshot(
        stickLx: Int = 0,
        dpad: Int = ProtocolConstants.DPAD_NEUTRAL,
        buttons: Int = 0,
        triggerR2: Int = 0,
        touchPressed: Boolean = true,
    ): InputReport =
        InputReport(
            seq = 0,
            stickLx = stickLx,
            stickLy = 0,
            stickRx = 0,
            stickRy = 0,
            dpad = dpad,
            buttons = buttons,
            triggerL2 = 0,
            triggerR2 = triggerR2,
            motion = null,
            touchX = 100,
            touchY = 200,
            touchPressed = touchPressed,
        )

    @Test
    fun `boton fisico mapea al bit canonical y se libera al soltar`() {
        val algo = PhysicalGamepadAlgo()
        assertTrue(algo.onKey(KeyCodes.BUTTON_A, pressed = true))
        assertEquals(ProtocolConstants.BUTTON_BIT_A, algo.buttons)
        assertTrue(algo.onKey(KeyCodes.BUTTON_B, pressed = true))
        assertEquals(ProtocolConstants.BUTTON_BIT_A or ProtocolConstants.BUTTON_BIT_B, algo.buttons)
        assertTrue(algo.onKey(KeyCodes.BUTTON_A, pressed = false))
        assertEquals(ProtocolConstants.BUTTON_BIT_B, algo.buttons)
    }

    @Test
    fun `distribucion nintendo intercambia a b y x y y deja el resto igual`() {
        // CR-032: con la distribución Nintendo activada, las teclas que el
        // SO reporta como A/B/X/Y llegan al juego como B/A/Y/X (la tecla A
        // tiene posición "abajo" en mandos Xbox/Play, que en Nintendo es B).
        val algo = PhysicalGamepadAlgo()
        algo.nintendoFaceDistribution = true
        assertTrue(algo.onKey(KeyCodes.BUTTON_A, pressed = true))
        assertEquals(ProtocolConstants.BUTTON_BIT_B, algo.buttons)
        assertTrue(algo.onKey(KeyCodes.BUTTON_B, pressed = true))
        assertEquals(ProtocolConstants.BUTTON_BIT_A or ProtocolConstants.BUTTON_BIT_B, algo.buttons)
        assertTrue(algo.onKey(KeyCodes.BUTTON_X, pressed = true))
        assertEquals(
            ProtocolConstants.BUTTON_BIT_A or ProtocolConstants.BUTTON_BIT_B or ProtocolConstants.BUTTON_BIT_Y,
            algo.buttons,
        )
        assertTrue(algo.onKey(KeyCodes.BUTTON_Y, pressed = true))
        assertEquals(
            ProtocolConstants.BUTTON_BIT_A or ProtocolConstants.BUTTON_BIT_B or
                ProtocolConstants.BUTTON_BIT_X or ProtocolConstants.BUTTON_BIT_Y,
            algo.buttons,
        )
        assertTrue(algo.onKey(KeyCodes.BUTTON_A, pressed = false))
        // Quedan las teclas B/X/Y presionadas (bits A/X/Y tras el intercambio).
        assertEquals(
            ProtocolConstants.BUTTON_BIT_A or ProtocolConstants.BUTTON_BIT_X or ProtocolConstants.BUTTON_BIT_Y,
            algo.buttons,
        )
        // L1 no participa en el intercambio de cara.
        assertTrue(algo.onKey(KeyCodes.BUTTON_L1, pressed = true))
        assertEquals(
            ProtocolConstants.BUTTON_BIT_A or ProtocolConstants.BUTTON_BIT_X or ProtocolConstants.BUTTON_BIT_Y or
                ProtocolConstants.BUTTON_BIT_L1,
            algo.buttons,
        )
    }

    @Test
    fun `distribucion nintendo fusiona igual con la superficie tactil`() {
        val algo = PhysicalGamepadAlgo()
        algo.nintendoFaceDistribution = true
        algo.onKey(KeyCodes.BUTTON_A, pressed = true)
        val fused = algo.fusedWith(touchSnapshot(buttons = ProtocolConstants.BUTTON_BIT_X))
        assertEquals(
            ProtocolConstants.BUTTON_BIT_B or ProtocolConstants.BUTTON_BIT_X,
            fused.buttons,
        )
    }

    @Test
    fun `keycode fuera de la signature no se consume`() {
        val algo = PhysicalGamepadAlgo()
        assertFalse(algo.handlesKey(4)) // BACK de android.view.KeyEvent
        assertFalse(algo.onKey(4, pressed = true))
        assertEquals(0, algo.buttons)
    }

    @Test
    fun `stick fisico vence a la superficie y el reposo no la pisa`() {
        val algo = PhysicalGamepadAlgo()
        algo.onMotion(motion(leftX = 1f))
        val fused = algo.fusedWith(touchSnapshot(stickLx = 40))
        assertEquals(ProtocolConstants.STICK_MAX, fused.stickLx)

        val idle = PhysicalGamepadAlgo()
        val fusedIdle = idle.fusedWith(touchSnapshot(stickLx = 40))
        assertEquals(40, fusedIdle.stickLx)
    }

    @Test
    fun `zona muerta cae a reposo sin reescalado`() {
        val algo = PhysicalGamepadAlgo()
        algo.onMotion(motion(leftX = 0.05f))
        assertEquals(0, algo.stickLx)
        algo.onMotion(motion(leftX = 0.5f, leftY = -0.5f))
        assertEquals((0.5f * ProtocolConstants.STICK_MAX).roundToInt(), algo.stickLx)
        assertEquals((-0.5f * ProtocolConstants.STICK_MAX).roundToInt(), algo.stickLy)
    }

    @Test
    fun `triggers por eje y por tecla fusionan el maximo con la superficie`() {
        val algo = PhysicalGamepadAlgo()
        algo.onMotion(motion(triggerL = 0.25f))
        algo.onKey(KeyCodes.BUTTON_R2, pressed = true)
        val fused = algo.fusedWith(touchSnapshot(triggerR2 = 128))
        assertEquals((0.25f * ProtocolConstants.TRIGGER_MAX).roundToInt(), fused.triggerL2)
        assertEquals(ProtocolConstants.TRIGGER_MAX, fused.triggerR2)
        assertTrue(algo.onKey(KeyCodes.BUTTON_R2, pressed = false))
        assertEquals(0, algo.triggerR2)
    }

    @Test
    fun `hat del dpad da direcciones y diagonales en sentido horario`() {
        val algo = PhysicalGamepadAlgo()
        algo.onHat(hatX = 0, hatY = -1)
        assertEquals(ProtocolConstants.DPAD_UP, algo.fusedWith(touchSnapshot()).dpad)
        algo.onHat(hatX = 1, hatY = 1)
        // SE físico manda aunque la superficie táctil tenga el Oeste presionado.
        assertEquals(3, algo.fusedWith(touchSnapshot(dpad = ProtocolConstants.DPAD_LEFT)).dpad)
        algo.onHat(hatX = 0, hatY = 0)
        // Hat en neutro: la dirección táctil conserva el elemento.
        assertEquals(
            ProtocolConstants.DPAD_LEFT,
            algo.fusedWith(touchSnapshot(dpad = ProtocolConstants.DPAD_LEFT)).dpad,
        )
    }

    @Test
    fun `teclas dpad simultaneas componen diagonal y las opuestas se anulan`() {
        val algo = PhysicalGamepadAlgo()
        algo.onKey(KeyCodes.DPAD_UP, pressed = true)
        algo.onKey(KeyCodes.DPAD_RIGHT, pressed = true)
        assertEquals(1, algo.dpad) // NE
        algo.onKey(KeyCodes.DPAD_LEFT, pressed = true)
        assertEquals(ProtocolConstants.DPAD_NEUTRAL, algo.dpad)
        algo.onKey(KeyCodes.DPAD_LEFT, pressed = false)
        assertEquals(1, algo.dpad)
        algo.onKey(KeyCodes.DPAD_RIGHT, pressed = false)
        assertEquals(ProtocolConstants.DPAD_UP, algo.dpad)
    }

    @Test
    fun `buttons es el or de ambas fuentes y touch viaja intacto`() {
        val algo = PhysicalGamepadAlgo()
        algo.onKey(KeyCodes.BUTTON_START, pressed = true)
        val fused =
            algo.fusedWith(
                touchSnapshot(
                    buttons = ProtocolConstants.BUTTON_BIT_A,
                    touchPressed = true,
                ),
            )
        assertEquals(
            ProtocolConstants.BUTTON_BIT_A or (1 shl 8),
            fused.buttons,
        )
        assertEquals(100, fused.touchX)
        assertEquals(200, fused.touchY)
        assertTrue(fused.touchPressed)
        assertEquals(0, fused.seq)
    }
}
