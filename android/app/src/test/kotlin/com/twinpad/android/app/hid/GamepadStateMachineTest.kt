package com.twinpad.android.app.hid

import com.twinpad.android.app.wire.InputCodec
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST-018 — verifies SPEC-004 (§6/§7/§11: flujo de inputs y orden por
 * `seq`; transporte inicial por sesión LAN, ADR-007).
 */
class GamepadStateMachineTest {
    private fun snapshot(
        buttons: Int = 0,
        stickLx: Int = 0,
        dpad: Int = ProtocolConstants.DPAD_NEUTRAL,
        triggerL2: Int = 0,
    ): InputReport =
        InputReport(
            seq = 0,
            stickLx = stickLx,
            stickLy = 0,
            stickRx = 0,
            stickRy = 0,
            dpad = dpad,
            buttons = buttons,
            triggerL2 = triggerL2,
            triggerR2 = 0,
        )

    @Test
    fun offline_does_not_emit_reports() {
        val gamepad = GamepadStateMachine()
        assertNull(gamepad.onElementChanged(snapshot(buttons = 1)))
        assertEquals(GamepadState.OFFLINE, gamepad.state)
    }

    @Test
    fun channel_ready_emits_initial_rest_report_with_seq_zero() {
        val gamepad = GamepadStateMachine()
        val initial = gamepad.onChannelReady()
        assertNotNull(initial)
        assertEquals(0, initial?.seq)
        assertEquals(ProtocolConstants.DPAD_NEUTRAL, initial?.dpad)
        assertEquals(GamepadState.READY, gamepad.state)
    }

    @Test
    fun seq_is_strictly_increasing_per_change() {
        val gamepad = GamepadStateMachine()
        gamepad.onChannelReady()
        val r1 = gamepad.onElementChanged(snapshot(buttons = 0b1)) // button_a
        val r2 = gamepad.onElementChanged(snapshot(buttons = 0b11)) // a + b
        val r3 = gamepad.onElementChanged(snapshot(buttons = 0b1))
        assertEquals(1, r1?.seq)
        assertEquals(2, r2?.seq)
        assertEquals(3, r3?.seq)
    }

    @Test
    fun no_change_emits_nothing() {
        val gamepad = GamepadStateMachine()
        gamepad.onChannelReady()
        gamepad.onElementChanged(snapshot(buttons = 0b1))
        assertNull(gamepad.onElementChanged(snapshot(buttons = 0b1)))
    }

    @Test
    fun values_are_clamped_to_signature_ranges() {
        val gamepad = GamepadStateMachine()
        gamepad.onChannelReady()
        val report =
            gamepad.onElementChanged(
                snapshot(
                    stickLx = 999,
                    triggerL2 = 300,
                    dpad = 20,
                ),
            )
        assertEquals(ProtocolConstants.STICK_MAX, report?.stickLx)
        assertEquals(ProtocolConstants.TRIGGER_MAX, report?.triggerL2)
        assertEquals(ProtocolConstants.DPAD_NEUTRAL, report?.dpad)
    }

    @Test
    fun channel_down_returns_to_offline_and_resets_seq() {
        val gamepad = GamepadStateMachine()
        gamepad.onChannelReady()
        gamepad.onElementChanged(snapshot(buttons = 0b1))
        gamepad.onChannelDown()
        assertNull(gamepad.onElementChanged(snapshot(buttons = 0b1)))
        val ready = gamepad.onChannelReady()
        assertEquals(0, ready?.seq) // nueva vida del canal: seq reinicia
    }

    /** Paridad wire con core::input: la línea es el NDJSON canónico. */
    @Test
    fun encoded_line_matches_core_format() {
        val gamepad = GamepadStateMachine()
        gamepad.onChannelReady()
        val report = gamepad.onElementChanged(snapshot(buttons = 0b1, stickLx = -127))
        val line = InputCodec.encode(report!!)
        assertFalse(line.contains('\n'))
        assert(line.startsWith("{\"type\":\"input_report\""))
        assert(line.contains("\"seq\":1"))
        assert(line.contains("\"buttons\":1"))
        assert(line.contains("\"stick_lx\":-127"))
        assertFalse(line.contains("gyro")) // §10: sin gyro el campo no viaja
    }
}
