package com.twinpad.android.app.ui

import com.twinpad.android.app.wire.MotionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-083 — verifies SPEC-004/CR-021: latest Android sensor events form one timestamped motion sample. */
class ProfileGyroControllerTest {
    @Test
    fun publishes_latest_acceleration_and_angular_velocity_with_monotonic_timestamp() {
        val samples = mutableListOf<MotionInput?>()
        val controller = ProfileGyroController { samples.add(it) }

        controller.updateAcceleration(floatArrayOf(0f, 9.8066f, 0f), 1_000_000L)
        assertNull(samples.last())
        controller.updateGyroscope(floatArrayOf(0.1f, -0.2f, 0.3f), 21_000_000L)
        controller.updateAcceleration(floatArrayOf(1f, 8f, -1f), 41_000_000L)

        assertEquals(
            MotionInput(41_000L, listOf(1f, 8f, -1f), listOf(0.1f, -0.2f, 0.3f)),
            samples.last(),
        )
    }

    @Test
    fun reset_omits_motion_until_both_sensors_report_again() {
        val samples = mutableListOf<MotionInput?>()
        val controller = ProfileGyroController { samples.add(it) }

        controller.updateAcceleration(floatArrayOf(0f, 0f, 9.8f), 1_000L)
        controller.updateGyroscope(floatArrayOf(0f, 0f, 0f), 2_000L)
        controller.reset()
        controller.updateGyroscope(floatArrayOf(1f, 0f, 0f), 3_000L)

        assertNull(samples.last())
    }
}
