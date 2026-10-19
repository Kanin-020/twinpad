package com.twinpad.android.app.ui

import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.view.Surface
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** TEST-059 — verifies CR-011: fixed landscape, immersive bars and screen-relative gyro axes. */
@RunWith(AndroidJUnit4::class)
class ScreenOrientationTest {
    @Test
    fun activity_is_fixed_landscape_and_hides_system_bars() {
        ActivityScenario.launch(PairingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
                val insets = WindowInsetsCompat.toWindowInsetsCompat(activity.window.decorView.rootWindowInsets)
                assertFalse("System status/navigation bars must be hidden", insets.isVisible(WindowInsetsCompat.Type.systemBars()))
            }
        }
    }

    @Test
    fun gyro_tilt_axes_follow_screen_rotation() {
        val angle = Math.toRadians(TILT_DEGREES.toDouble()).toFloat()
        val rotationVector = floatArrayOf(sin(angle / 2f), 0f, 0f, cos(angle / 2f))
        val expectations =
            listOf(
                Surface.ROTATION_0 to true,
                Surface.ROTATION_90 to false,
                Surface.ROTATION_180 to true,
                Surface.ROTATION_270 to false,
            )

        expectations.forEach { (rotation, tiltAppearsAsPitch) ->
            val matrix = FloatArray(9)
            val angles = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
            remapOrientationMatrix(matrix, rotation)
            SensorManager.getOrientation(matrix, angles)

            val pitch = abs(angles[1])
            val roll = abs(angles[2])
            if (tiltAppearsAsPitch) {
                assertTrue("Rotation $rotation should map device X tilt to screen pitch", pitch > MIN_EXPECTED_ANGLE)
                assertTrue("Rotation $rotation should not leave the tilt on screen roll", roll < MAX_CROSS_AXIS_ANGLE)
            } else {
                assertTrue("Rotation $rotation should map device X tilt to screen roll", roll > MIN_EXPECTED_ANGLE)
                assertTrue("Rotation $rotation should not leave the tilt on screen pitch", pitch < MAX_CROSS_AXIS_ANGLE)
            }
        }
    }

    private companion object {
        const val TILT_DEGREES = 30
        const val MIN_EXPECTED_ANGLE = 0.4f
        const val MAX_CROSS_AXIS_ANGLE = 0.05f
    }
}
