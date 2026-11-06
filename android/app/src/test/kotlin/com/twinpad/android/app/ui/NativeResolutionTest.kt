package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-0151 — verifies SPEC-002 (CR-074): la opción «Nativa» del drawer
 * (width=0, height=0) viaja como `video_config` con `0/0` (centinela de
 * nativo que el server reenvía sin clamp y Cemu resuelve a la dimensión
 * del DRC); el default 1080p (CR-031) queda intacto. */
class NativeResolutionTest {
    @Test
    fun nativeOptionSendsZeroZeroWireAndDefaultStays1080p() {
        assertEquals(
            "{\"type\":\"video_config\",\"w\":1920,\"h\":1080,\"bitrate_kbps\":20000}",
            AndroidSettings().videoConfigLine(),
        )
        // CR-087: sin frame conocido, «Nativa» parte de la clase 540p.
        assertEquals(
            "{\"type\":\"video_config\",\"w\":0,\"h\":0,\"bitrate_kbps\":10000}",
            AndroidSettings(width = 0, height = 0).videoConfigLine(),
        )
    }
}
