package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-0183 — verifies SPEC-002 (CR-087): el bitrate del encoder se deriva
 * de la resolución de emisión (540p 10 Mbps, 720p 15 Mbps, 1080p 20 Mbps),
 * cualquier cambio de resolución lo actualiza en el `video_config` y
 * «Nativa» usa la clase de la dimensión real anunciada por `stream_start`. */
class VideoBitrateTest {
    @Test
    fun bitrate_follows_the_selected_resolution() {
        assertEquals(10_000, bitrateForResolution(960, 540))
        assertEquals(15_000, bitrateForResolution(1280, 720))
        assertEquals(20_000, bitrateForResolution(1920, 1080))
        // Fuera de las opciones del drawer: se conserva la clase por altura.
        assertEquals(10_000, bitrateForResolution(854, 480))
        assertEquals(10_000, bitrateForResolution(1918, 540))
        assertEquals(20_000, bitrateForResolution(2560, 1440))
        // «Nativa» sin frame conocido: clase 540p.
        assertEquals(10_000, bitrateForResolution(0, 0))
    }

    @Test
    fun changing_resolution_updates_the_wire_bitrate() {
        // El drawer cambia la resolución con `copy`; el bitrate debe seguirlo
        // sin quedar con el valor de la clase anterior.
        val from1080p = AndroidSettings().copy(width = 1280, height = 720)
        assertEquals(
            "{\"type\":\"video_config\",\"w\":1280,\"h\":720,\"bitrate_kbps\":15000}",
            from1080p.videoConfigLine(),
        )
        val from720p = from1080p.copy(width = 960, height = 540)
        assertEquals(
            "{\"type\":\"video_config\",\"w\":960,\"h\":540,\"bitrate_kbps\":10000}",
            from720p.videoConfigLine(),
        )
    }

    @Test
    fun native_uses_the_announced_stream_dimensions() {
        // CR-087: «Nativa» viaja como 0/0 y el bitrate lo fija la dimensión
        // real del `stream_start`, no el par nativo del drawer.
        val native = AndroidSettings(width = 0, height = 0)
        assertEquals(
            "{\"type\":\"video_config\",\"w\":0,\"h\":0,\"bitrate_kbps\":20000}",
            native.videoConfigLine(bitrateForResolution(1920, 1080)),
        )
        assertEquals(
            "{\"type\":\"video_config\",\"w\":0,\"h\":0,\"bitrate_kbps\":15000}",
            native.videoConfigLine(bitrateForResolution(1280, 720)),
        )
        assertEquals(
            "{\"type\":\"video_config\",\"w\":0,\"h\":0,\"bitrate_kbps\":10000}",
            native.videoConfigLine(),
        )
    }
}
