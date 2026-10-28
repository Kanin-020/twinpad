package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-069 — verifies local drawer settings preserve stream configuration values and wire output (CR-027 bitrate; CR-029 sin fps). */
class AndroidSettingsStoreTest {
    @Test
    fun stream_settings_encode_all_adjustable_values_together() {
        val settings =
            AndroidSettings(
                width = 960,
                height = 540,
                videoBitrateKbps = 12_000,
                audioVolumePercent = 35,
                showHud = false,
                showControls = false,
            )

        // CR-029: `video_config` ya no lleva `fps` (emisión unlimited).
        assertEquals(
            "{\"type\":\"video_config\",\"w\":960,\"h\":540,\"bitrate_kbps\":12000}",
            settings.videoConfigLine(),
        )
        assertEquals(35, settings.audioVolumePercent)
        assertEquals(false, settings.showHud)
        assertEquals(false, settings.showControls)
    }

    @Test
    fun stream_settings_have_stable_defaults() {
        // CR-031: fábrica = 1080p, bitrate máximo (30.000 kbps), escalado
        // "contener" y HUD fps / botones visibles.
        val defaults = AndroidSettings()
        assertEquals(1920, defaults.width)
        assertEquals(1080, defaults.height)
        assertEquals(30_000, defaults.videoBitrateKbps)
        assertEquals(AndroidSettings.DEFAULT_AUDIO_VOLUME_PERCENT, defaults.audioVolumePercent)
        assertEquals(VideoScaleMode.CONTAIN, defaults.scaleMode)
        assertEquals(true, defaults.showHud)
        assertEquals(true, defaults.showControls)
    }
}
