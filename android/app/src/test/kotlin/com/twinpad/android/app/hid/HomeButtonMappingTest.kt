package com.twinpad.android.app.hid

import com.twinpad.android.app.hid.PhysicalGamepadAlgo.KeyCodes
import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0213 — CR-120: el botón Home físico es el Guide/PS del mando
 * (`KEYCODE_BUTTON_MODE`), no un clic de stick; L3 y R3 usan sus keycodes
 * de `KeyEvent` (`BUTTON_THUMBL`/`BUTTON_THUMBR`) y la fila del mapeador
 * que lo asigna se llama «Home».
 */
class HomeButtonMappingTest {
    @Test
    fun physical_keycodes_match_the_android_constants() {
        assertEquals(106, PhysicalKeys.BUTTON_L3)
        assertEquals(107, PhysicalKeys.BUTTON_R3)
        assertEquals(110, PhysicalKeys.BUTTON_MODE)
    }

    @Test
    fun the_default_mapping_sends_home_to_extra_1_and_not_to_l3() {
        val algo = PhysicalGamepadAlgo()
        assertTrue(algo.onKey(PhysicalKeys.BUTTON_MODE, pressed = true))
        assertEquals(ProtocolConstants.BUTTON_BIT_EXTRA_1, algo.buttons)
        assertEquals(0, algo.buttons and (1 shl 6)) // bit de L3 intacto
        assertTrue(algo.onKey(PhysicalKeys.BUTTON_MODE, pressed = false))
        assertEquals(0, algo.buttons)
    }

    @Test
    fun stick_clicks_keep_their_own_bits() {
        val algo = PhysicalGamepadAlgo()
        algo.onKey(PhysicalKeys.BUTTON_L3, pressed = true)
        assertEquals(1 shl 6, algo.buttons)
        algo.onKey(PhysicalKeys.BUTTON_R3, pressed = true)
        assertEquals((1 shl 6) or (1 shl 7), algo.buttons)
    }

    @Test
    fun home_is_listed_and_assignable_in_the_physical_mapper() {
        val home = MappedControl.EXTRA_1
        assertEquals("Home", home.label)
        assertTrue(home.button)
        assertFalse(home.stickAxis)
        assertEquals(listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_MODE)), GamepadMapping.DEFAULT.inputsOf(home))
        assertTrue(GamepadMapping.DEFAULT.handlesKey(KeyCodes.BUTTON_MODE))
    }
}
