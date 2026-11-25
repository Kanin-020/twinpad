package com.twinpad.android.app.hid

import com.twinpad.android.app.hid.PhysicalGamepadAlgo.KeyCodes
import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0209 — SPEC-011: mapeo predeterminado, captura de botón/eje,
 * duplicados y composición de cruceta sobre el mapper físico.
 */
class GamepadMappingTest {
    @Test
    fun default_mapping_matches_the_automatic_equivalences() {
        val algo = PhysicalGamepadAlgo()
        assertTrue(algo.onKey(KeyCodes.BUTTON_A, pressed = true))
        assertEquals(1, algo.buttons and 1)
        assertTrue(algo.onKey(KeyCodes.BUTTON_A, pressed = false))

        algo.onAxes(mapOf(PhysicalAxes.AXIS_X to 0.5f))
        assertEquals(64, algo.stickLx)
        algo.onAxes(mapOf(PhysicalAxes.AXIS_Z to -0.5f))
        assertEquals(-63, algo.stickRx)

        algo.onAxes(mapOf(PhysicalAxes.AXIS_LTRIGGER to 0.5f))
        assertEquals(128, algo.triggerL2)

        algo.onAxes(mapOf(PhysicalAxes.AXIS_HAT_Y to -1f))
        assertEquals(ProtocolConstants.DPAD_UP, algo.dpad)
        algo.onAxes(mapOf(PhysicalAxes.AXIS_HAT_Y to -1f, PhysicalAxes.AXIS_HAT_X to 1f))
        assertEquals(1, algo.dpad)
    }

    @Test
    fun capturing_an_axis_keeps_the_largest_moved_axis_and_its_sign() {
        assertNull(captureAxis(mapOf(PhysicalAxes.AXIS_X to 0.3f)))
        assertEquals(
            PhysicalInput.Axis(PhysicalAxes.AXIS_RX, -1),
            captureAxis(
                mapOf(
                    PhysicalAxes.AXIS_RX to -0.9f,
                    PhysicalAxes.AXIS_HAT_X to 0.6f,
                ),
            ),
        )
        assertEquals(
            PhysicalInput.Axis(PhysicalAxes.AXIS_RTRIGGER, 1),
            captureAxis(mapOf(PhysicalAxes.AXIS_RTRIGGER to 0.8f)),
        )
    }

    @Test
    fun a_captured_binding_replaces_the_equivalence_and_duplicates_are_allowed() {
        val custom =
            GamepadMapping.DEFAULT
                .with(MappedControl.STICK_LX, PhysicalInput.Axis(PhysicalAxes.AXIS_RX))
                .with(MappedControl.A, PhysicalInput.Key(KeyCodes.BUTTON_B))
        val algo = PhysicalGamepadAlgo()
        algo.mapping = custom

        algo.onAxes(mapOf(PhysicalAxes.AXIS_RX to -1f))
        assertEquals(-127, algo.stickLx)
        algo.onAxes(mapOf(PhysicalAxes.AXIS_RX to 0.5f))
        assertEquals(64, algo.stickLx)

        algo.onKey(KeyCodes.BUTTON_B, pressed = true)
        // Se reemplazó la equivalencia de A; B sigue activando B (duplicado).
        assertEquals(1 or (1 shl 1), algo.buttons)
    }

    @Test
    fun dpad_accepts_a_captured_axis_and_keys_compose_diagonals() {
        val custom =
            GamepadMapping.DEFAULT
                .with(MappedControl.DPAD_LEFT, PhysicalInput.Axis(PhysicalAxes.AXIS_RX, -1))
        val algo = PhysicalGamepadAlgo()
        algo.mapping = custom

        algo.onAxes(mapOf(PhysicalAxes.AXIS_RX to -1f))
        assertEquals(ProtocolConstants.DPAD_LEFT, algo.dpad)

        algo.onKey(KeyCodes.DPAD_UP, pressed = true)
        assertEquals(7, algo.dpad) // noroeste
    }

    @Test
    fun the_model_identity_uses_the_input_device_fields() {
        assertEquals("v1356|p3302|DualSense", gamepadModelKey(1356, 3302, " DualSense ", "descriptor"))
        assertEquals("v1|p2|Gamepad", gamepadModelKey(1, 2, "Gamepad", null))
        assertEquals("descriptor", gamepadModelKey(0, 0, "  ", " descriptor "))
        assertEquals("unknown", gamepadModelKey(0, 0, null, null))
    }
}
