package com.twinpad.android.app.hid

import com.twinpad.android.app.hid.PhysicalGamepadAlgo.KeyCodes
import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0217 — SPEC-011 §7 paso 4 (CR-123) y §6 M3 (CR-124): una fila de
 * botón acepta una combinación pulsada a la vez, se guarda como una sola
 * entrada, solo se activa con todas sus teclas y la captura caduca sin input.
 */
class GamepadCombinationTest {
    private val combo = PhysicalInput.Combo(listOf(KeyCodes.BUTTON_L1, KeyCodes.BUTTON_R1))

    @Test
    fun capture_returns_the_widest_combination_once_every_key_is_released() {
        val capture = ChordCapture()
        capture.press(KeyCodes.BUTTON_L1)
        capture.press(KeyCodes.BUTTON_R1)
        assertNull(capture.release(KeyCodes.BUTTON_L1))
        assertEquals(listOf(KeyCodes.BUTTON_L1, KeyCodes.BUTTON_R1), capture.release(KeyCodes.BUTTON_R1))
    }

    @Test
    fun capture_of_a_single_button_is_unchanged_and_sequential_presses_do_not_merge() {
        val capture = ChordCapture()
        capture.press(KeyCodes.BUTTON_A)
        assertEquals(listOf(KeyCodes.BUTTON_A), capture.release(KeyCodes.BUTTON_A))
        capture.press(KeyCodes.BUTTON_B)
        assertEquals(listOf(KeyCodes.BUTTON_B), capture.release(KeyCodes.BUTTON_B))

        assertNull(captureKeys(emptyList()))
        assertEquals(PhysicalInput.Key(KeyCodes.BUTTON_A), captureKeys(listOf(KeyCodes.BUTTON_A)))
        assertEquals(
            combo,
            captureKeys(listOf(KeyCodes.BUTTON_R1, KeyCodes.BUTTON_L1, KeyCodes.BUTTON_R1)),
        )
    }

    @Test
    fun combination_replaces_the_row_and_survives_the_profile_codec() {
        val mapping = GamepadMapping.DEFAULT.with(MappedControl.EXTRA_1, combo)
        assertEquals(listOf(combo), mapping.inputsOf(MappedControl.EXTRA_1))
        assertTrue(mapping.handlesKey(KeyCodes.BUTTON_L1))
        assertTrue(mapping.handlesKey(KeyCodes.BUTTON_R1))
        assertFalse(mapping.handlesKey(KeyCodes.BUTTON_MODE))

        val set =
            GamepadProfileSet(
                modelKey = "v1|p2|Test",
                active = "Perfil 1",
                profiles = listOf(GamepadProfile("Perfil 1", mapping)),
            )
        val text = GamepadProfileCodec.encode(set)
        assertTrue(text.contains("EXTRA_1=c:${KeyCodes.BUTTON_L1}+${KeyCodes.BUTTON_R1}"))
        assertEquals(
            listOf(combo),
            GamepadProfileCodec.decode(text)?.activeProfile()?.mapping?.inputsOf(MappedControl.EXTRA_1),
        )
    }

    @Test
    fun combination_only_fires_with_every_key_pressed() {
        val algo = PhysicalGamepadAlgo()
        algo.mapping = GamepadMapping.DEFAULT.with(MappedControl.EXTRA_1, combo)

        algo.onKey(KeyCodes.BUTTON_L1, pressed = true)
        assertEquals(0, algo.buttons and ProtocolConstants.BUTTON_BIT_EXTRA_1)
        algo.onKey(KeyCodes.BUTTON_R1, pressed = true)
        assertEquals(
            ProtocolConstants.BUTTON_BIT_EXTRA_1,
            algo.buttons and ProtocolConstants.BUTTON_BIT_EXTRA_1,
        )
        algo.onKey(KeyCodes.BUTTON_L1, pressed = false)
        assertEquals(0, algo.buttons and ProtocolConstants.BUTTON_BIT_EXTRA_1)
    }

    @Test
    fun countdown_expires_after_ten_seconds_without_input() {
        val countdown = CaptureCountdown()
        assertEquals(10, countdown.remaining)
        repeat(9) { assertFalse(countdown.tick()) }
        assertEquals(1, countdown.remaining)
        assertTrue(countdown.tick())

        countdown.restart()
        assertEquals(10, countdown.remaining)
    }
}
