package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-069/TEST-089 — local drawer settings preserve stream configuration
 * values and wire output (CR-027 bitrate; CR-029 sin fps; CR-032 la
 * distribución Nintendo arranca apagada y no altera el wire). */
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
        // CR-054: la preferencia es local (no altera ningún wire).
        val advanced = settings.copy(showAdvancedMetrics = true)
        assertEquals(true, advanced.showAdvancedMetrics)
        assertEquals(settings.videoConfigLine(), advanced.videoConfigLine())
        // CR-077: el origen de los gauges de espera también es local.
        val gamepadSource = settings.copy(metricsSourceHost = false)
        assertEquals(false, gamepadSource.metricsSourceHost)
        assertEquals(settings.videoConfigLine(), gamepadSource.videoConfigLine())
        // CR-079 (TEST-0160): el slider de gauges también es local y no
        // altera el contrato video_config.
        val sliderHidden = settings.copy(metricsSliderOverStream = false)
        assertEquals(false, sliderHidden.metricsSliderOverStream)
        assertEquals(settings.videoConfigLine(), sliderHidden.videoConfigLine())
    }

    @Test
    fun stream_settings_have_stable_defaults() {
        // CR-031: fábrica = 1080p, bitrate fijo 10 000 kbps (el slider del
        // drawer se eliminó a petición del owner), escalado "contener" y HUD
        // fps / botones visibles.
        val defaults = AndroidSettings()
        assertEquals(1920, defaults.width)
        assertEquals(1080, defaults.height)
        assertEquals(10_000, defaults.videoBitrateKbps)
        assertEquals(AndroidSettings.DEFAULT_AUDIO_VOLUME_PERCENT, defaults.audioVolumePercent)
        assertEquals(VideoScaleMode.CONTAIN, defaults.scaleMode)
        assertEquals(true, defaults.showHud)
        assertEquals(true, defaults.showControls)
        // CR-032: la distribución de cara arranca en Xbox/Play.
        assertEquals(false, defaults.nintendoDistribution)
        // CR-054: las métricas avanzadas arrancan apagadas (opt-in).
        assertEquals(false, defaults.showAdvancedMetrics)
        // CR-077: el alternador arranca mostrando la PC (CR-076 v2).
        assertEquals(true, defaults.metricsSourceHost)
        // CR-079 (TEST-0160): el slider nace activo (hoja abierta al entrar
        // en sesión, decisión del owner).
        assertEquals(true, defaults.metricsSliderOverStream)
    }

    @Test
    fun `nintendo distribution no altera el contrato video_config`() {
        // CR-032: la preferencia es local (SPEC-002 R19); el wire no cambia.
        val settings =
            AndroidSettings(
                width = 1920,
                height = 1080,
                videoBitrateKbps = 30_000,
                nintendoDistribution = true,
            )
        assertEquals(
            "{\"type\":\"video_config\",\"w\":1920,\"h\":1080,\"bitrate_kbps\":30000}",
            settings.videoConfigLine(),
        )
        assertEquals(VideoScaleMode.CONTAIN, settings.scaleMode)
    }
}
