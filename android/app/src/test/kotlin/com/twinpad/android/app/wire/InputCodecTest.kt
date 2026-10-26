package com.twinpad.android.app.wire

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST-083 — verifies SPEC-004/CR-021: Android serializes DSU-style inertial motion samples. */
class InputCodecTest {
    @Test
    fun motion_report_preserves_monotonic_timestamp_acceleration_and_angular_velocity() {
        val report =
            InputReport(
                seq = 7,
                stickLx = 0,
                stickLy = 0,
                stickRx = 0,
                stickRy = 0,
                dpad = ProtocolConstants.DPAD_NEUTRAL,
                buttons = 0,
                triggerL2 = 0,
                triggerR2 = 0,
                motion = MotionInput(123_456_789, listOf(0f, 9.8066f, 0f), listOf(0.1f, -0.2f, 0.3f)),
            )

        val line = InputCodec.encode(report)

        assertTrue(line.contains("\"motion\":{\"timestamp_us\":123456789,"))
        assertTrue(line.contains("\"accel\":[0.0,9.8066,0.0]"))
        assertTrue(line.contains("\"gyro\":[0.1,-0.2,0.3]"))
        assertFalse(line.contains("\"yaw\""))
    }

    @Test
    fun missing_sensor_sample_omits_motion_object() {
        val report =
            InputReport(
                seq = 1,
                stickLx = 0,
                stickLy = 0,
                stickRx = 0,
                stickRy = 0,
                dpad = ProtocolConstants.DPAD_NEUTRAL,
                buttons = 0,
                triggerL2 = 0,
                triggerR2 = 0,
            )

        assertFalse(InputCodec.encode(report).contains("\"motion\""))
    }
}
