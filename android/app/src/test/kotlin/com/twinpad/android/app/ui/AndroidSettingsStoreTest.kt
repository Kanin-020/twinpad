package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-069 — verifies local drawer settings preserve stream configuration values and wire output. */
class AndroidSettingsStoreTest {
    @Test
    fun stream_settings_encode_all_adjustable_values_together() {
        val settings =
            AndroidSettings(
                width = 960,
                height = 540,
                fps = 60,
                jpegQuality = 72,
                audioVolumePercent = 35,
                showHud = false,
                showControls = false,
            )

        assertEquals(
            "{\"type\":\"video_config\",\"w\":960,\"h\":540,\"fps\":60,\"jpeg_quality\":72}",
            settings.videoConfigLine(),
        )
        assertEquals(35, settings.audioVolumePercent)
        assertEquals(false, settings.showHud)
        assertEquals(false, settings.showControls)
    }

    @Test
    fun stream_settings_have_stable_defaults() {
        assertEquals(
            AndroidSettings.DEFAULT_WIDTH to AndroidSettings.DEFAULT_HEIGHT,
            AndroidSettings().width to AndroidSettings().height,
        )
        assertEquals(AndroidSettings.DEFAULT_FPS, AndroidSettings().fps)
        assertEquals(AndroidSettings.DEFAULT_JPEG_QUALITY, AndroidSettings().jpegQuality)
        assertEquals(AndroidSettings.DEFAULT_AUDIO_VOLUME_PERCENT, AndroidSettings().audioVolumePercent)
    }
}
