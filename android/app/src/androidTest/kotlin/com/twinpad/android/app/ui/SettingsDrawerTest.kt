package com.twinpad.android.app.ui

import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twinpad.android.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** TEST-061 — verifies SPEC-002 settings drawer remains usable on short screens. */
@RunWith(AndroidJUnit4::class)
class SettingsDrawerTest {
    @Test
    fun saved_stream_and_volume_settings_survive_store_reload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidSettingsStore(context)
        val original = store.load()
        val updated = original.copy(width = 960, height = 540, fps = 60, videoBitrateKbps = 12_500, audioVolumePercent = 42)
        try {
            store.save(updated)
            assertEquals(updated, store.load())
        } finally {
            store.save(original)
        }
    }

    @Test
    fun settings_drawer_scrolls_to_lower_controls() {
        val scenario = ActivityScenario.launch(PairingActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val drawer = activity.findViewById<DrawerLayout>(R.id.settings_drawer)
                drawer.openDrawer(GravityCompat.START)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val includedDrawer = activity.findViewById<View>(R.id.settings_drawer_content)
                val scroll = includedDrawer as androidx.core.widget.NestedScrollView
                val drawerContent = scroll.getChildAt(0)
                assertTrue(
                    "Drawer content should exceed its viewport",
                    drawerContent.height > scroll.height,
                )
                assertTrue(
                    "Drawer should scroll to the bottom controls",
                    scroll.fullScroll(android.view.View.FOCUS_DOWN),
                )
                val closeButton = activity.findViewById<android.view.View>(R.id.btn_close_drawer)
                assertTrue(
                    "Close button should be reachable after scrolling",
                    closeButton.bottom <= drawerContent.height,
                )
            }
        } finally {
            scenario.close()
        }
    }
}
