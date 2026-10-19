package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-060 — verifies SPEC-004: screen-relative azimuth/pitch/roll retain wire yaw/pitch/roll order. */
class GyroOrientationMapperTest {
    @Test
    fun screen_relative_angles_preserve_the_wire_yaw_pitch_roll_order() {
        assertEquals(
            listOf(8_192, 0, 0),
            GyroOrientationMapper.toScreenTurns(
                floatArrayOf((Math.PI / 2).toFloat(), 0f, 0f),
                scale = 32_767f,
            ),
        )
        assertEquals(
            listOf(0, 0, 8_192),
            GyroOrientationMapper.toScreenTurns(
                floatArrayOf(0f, 0f, (Math.PI / 2).toFloat()),
                scale = 32_767f,
            ),
        )
    }
}
