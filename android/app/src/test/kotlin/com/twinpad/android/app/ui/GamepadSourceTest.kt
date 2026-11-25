package com.twinpad.android.app.ui

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0211 — regresión del filtro de origen (SPEC-011 §2): `SOURCE_GAMEPAD`
 * comparte el bit de clase «botón», así que un teclado o el D-pad virtual no
 * pueden tratarse como mando.
 */
class GamepadSourceTest {
    @Test
    fun keyboards_and_virtual_dpads_are_not_gamepads() {
        assertFalse(isGamepadSource(InputDevice.SOURCE_KEYBOARD))
        assertFalse(isGamepadSource(InputDevice.SOURCE_DPAD))
        assertFalse(isGamepadSource(InputDevice.SOURCE_MOUSE))
    }

    @Test
    fun gamepads_and_joysticks_are_gamepads_even_with_extra_sources() {
        assertTrue(isGamepadSource(InputDevice.SOURCE_GAMEPAD))
        assertTrue(isGamepadSource(InputDevice.SOURCE_JOYSTICK))
        assertTrue(isGamepadSource(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK))
        assertTrue(isGamepadSource(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD))
    }
}
