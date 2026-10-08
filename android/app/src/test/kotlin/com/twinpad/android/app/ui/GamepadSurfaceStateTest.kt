package com.twinpad.android.app.ui

import com.twinpad.android.app.hid.GamepadStateMachine
import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

/**
 * TEST-018 — verifies SPEC-004 (§2/§4/§7: sticks analógicos −127…127 con
 * centro 0 y triggers analógicos 0…255 alimentados desde la superficie
 * táctil; orden por `seq`).
 *
 * La maquinaria pura del mapeo vive en [GamepadSurfaceState] (extraída de
 * la UI para que la ruta de inputs sea testeable en JVM sin Android, ENG
 * §5 P10/P11).
 */
class GamepadSurfaceStateTest {
    @Test
    fun stick_normalization_clamps_to_int8_range() {
        val surface = GamepadSurfaceState()
        surface.setStick(1f, -1f, isLeft = true)
        val snapshot = surface.snapshot()
        assertEquals(ProtocolConstants.STICK_MAX, snapshot.stickLx)
        assertEquals(ProtocolConstants.STICK_MIN, snapshot.stickLy)
    }

    @Test
    fun stick_center_reports_zero() {
        val surface = GamepadSurfaceState()
        surface.setStick(0.5f, 0.5f, isLeft = true)
        surface.setStick(0f, 0f, isLeft = true)
        val snapshot = surface.snapshot()
        assertEquals(0, snapshot.stickLx)
        assertEquals(0, snapshot.stickLy)
    }

    @Test
    fun trigger_normalization_clamps_to_unsigned_range() {
        val surface = GamepadSurfaceState()
        surface.setTrigger(1f, isLeft = true)
        surface.setTrigger(0.5f, isLeft = false)
        val snapshot = surface.snapshot()
        assertEquals(ProtocolConstants.TRIGGER_MAX, snapshot.triggerL2)
        assertEquals((ProtocolConstants.TRIGGER_MAX * 0.5f).roundToInt(), snapshot.triggerR2)
    }

    @Test
    fun negative_trigger_value_is_clamped_to_rest() {
        val surface = GamepadSurfaceState()
        surface.setTrigger(-3f, isLeft = false)
        assertEquals(0, surface.snapshot().triggerR2)
    }

    @Test
    fun left_and_right_sticks_are_independent() {
        val surface = GamepadSurfaceState()
        surface.setStick(1f, 0f, isLeft = true)
        surface.setStick(-1f, 0f, isLeft = false)
        val snapshot = surface.snapshot()
        assertEquals(ProtocolConstants.STICK_MAX, snapshot.stickLx)
        assertEquals(ProtocolConstants.STICK_MIN, snapshot.stickRx)
    }

    /** La máquina del gamepad asigna `seq` a los reportes de la superficie (R2). */
    @Test
    fun surface_changes_flow_through_the_state_machine_with_increasing_seq() {
        val surface = GamepadSurfaceState()
        val gamepad = GamepadStateMachine()
        gamepad.onChannelReady()
        // El reporte en reposo ya lo envió onChannelReady (§6 H0→H1):
        // la misma instantánea de la superficie no re-emite (§10).
        assertNull(gamepad.onElementChanged(surface.snapshot()))

        surface.setStick(0f, -1f, isLeft = true)
        val r2 = gamepad.onElementChanged(surface.snapshot())
        assertEquals(ProtocolConstants.STICK_MIN, r2?.stickLy)

        // Mismo estado: la máquina no emite (§10).
        assertNull(gamepad.onElementChanged(surface.snapshot()))

        surface.setStick(0f, 0f, isLeft = true)
        val r3 = gamepad.onElementChanged(surface.snapshot())
        assertNotNull(r3)
        assertEquals(0, r3?.stickLy)
    }
}
