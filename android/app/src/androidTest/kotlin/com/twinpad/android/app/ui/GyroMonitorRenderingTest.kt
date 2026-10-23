package com.twinpad.android.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twinpad.android.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** TEST-078 — verifies SPEC-002/SPEC-004: optional Android orientation monitor. */
@RunWith(AndroidJUnit4::class)
class GyroMonitorRenderingTest {
    @Test
    fun monitor_is_hidden_by_default_and_persists_opt_in() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("twinpad_user_settings", android.content.Context.MODE_PRIVATE)
        val originalValue = preferences.getBoolean("show_gyro_monitor", false)
        preferences.edit().remove("show_gyro_monitor").commit()
        val scenario = ActivityScenario.launch(PairingActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val store = AndroidSettingsStore(activity)
                val original = store.load()
                try {
                    assertTrue("The monitor preference defaults to disabled", !store.load().showGyroMonitor)
                    store.save(original.copy(showGyroMonitor = false))
                    assertEquals(
                        "The monitor is initially disabled in the launched activity",
                        android.view.View.GONE,
                        activity.findViewById<GyroMonitorView>(R.id.gyro_monitor).visibility,
                    )
                    val gyroSwitch =
                        activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                            R.id.switch_show_gyro_monitor,
                        )
                    gyroSwitch.performClick()
                    assertEquals(
                        "The settings switch shows the monitor over the non-session Pairing view",
                        android.view.View.VISIBLE,
                        activity.findViewById<GyroMonitorView>(R.id.gyro_monitor).visibility,
                    )
                    assertTrue("The settings switch persists the opt-in", store.load().showGyroMonitor)
                    gyroSwitch.performClick()
                    assertEquals(
                        "The switch hides the monitor again when opting out",
                        android.view.View.GONE,
                        activity.findViewById<GyroMonitorView>(R.id.gyro_monitor).visibility,
                    )
                    assertTrue("The opt-out preference is persisted", !store.load().showGyroMonitor)
                } finally {
                    store.save(store.load().copy(showGyroMonitor = originalValue))
                }
            }
        } finally {
            scenario.close()
            preferences.edit().putBoolean("show_gyro_monitor", originalValue).commit()
        }
    }

    @Test
    fun monitor_draws_axes_and_current_orientation_values() {
        val scenario = ActivityScenario.launch(PairingActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val monitor = GyroMonitorView(activity)
                monitor.layout(0, 0, 480, 304)
                monitor.setOrientationTurns(8192, -4096, 16384)
                val bitmap = Bitmap.createBitmap(480, 304, Bitmap.Config.ARGB_8888)
                try {
                    monitor.draw(Canvas(bitmap))
                    val paintedPixels =
                        (0 until bitmap.height).sumOf { y ->
                            (0 until bitmap.width).count { x ->
                                bitmap.getPixel(x, y) != android.graphics.Color.TRANSPARENT
                            }
                        }
                    assertTrue("Monitor frame, axis lines, and text should be painted", paintedPixels > 2_000)
                    assertEquals(8192, monitor.orientationForTest()[0])
                    assertEquals(-4096, monitor.orientationForTest()[1])
                    assertEquals(16384, monitor.orientationForTest()[2])
                    val expectedAxisColors =
                        intArrayOf(
                            0xFF65D681.toInt(),
                            0xFFFF5B5B.toInt(),
                            0xFF64A9FF.toInt(),
                        )
                    assertTrue(
                        "Yaw, pitch and roll use green, red and blue, respectively",
                        monitor.axisColorsForTest().contentEquals(expectedAxisColors),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            scenario.close()
        }
    }
}
