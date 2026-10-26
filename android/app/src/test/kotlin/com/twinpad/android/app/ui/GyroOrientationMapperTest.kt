package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-060 — verifies SPEC-004/CR-021: Android orientation remains a local diagnostic, not the motion wire payload. */
class GyroOrientationMapperTest {
    @Test
    fun local_orientation_maps_to_monitor_turns_in_yaw_pitch_roll_order() {
        assertEquals(
            listOf(8_192, 0, 0),
            GyroOrientationMapper.toScreenTurns(floatArrayOf((Math.PI / 2).toFloat(), 0f, 0f)),
        )
        assertEquals(
            listOf(0, 0, 8_192),
            GyroOrientationMapper.toScreenTurns(floatArrayOf(0f, 0f, (Math.PI / 2).toFloat())),
        )
    }
}
