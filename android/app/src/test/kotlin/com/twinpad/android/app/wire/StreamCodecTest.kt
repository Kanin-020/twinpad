package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-074 — verifies SPEC-003/CR-014: H.264 start contract and fail-closed parsing. */
class StreamCodecTest {
    @Test
    fun stream_start_accepts_h264_media_contract_and_rejects_legacy_or_unknown_formats() {
        val startLine =
            """{"type":"stream_start","w":1280,"h":720,"fmt":"h264","fps":30,"media_port":8425}"""
        val start = StreamCodec.decode(startLine) as StreamMessage.Start
        assertEquals("h264", start.fmt)
        assertEquals(8425, start.mediaPort)
        assertInvalid("""{"type":"stream_start","w":1280,"h":720,"fmt":"jpeg_tiles","fps":30,"media_port":8425}""")
        assertInvalid("""{"type":"stream_start","w":1280,"h":720,"fmt":"future","fps":30,"media_port":8425}""")
        assertInvalid("""{"type":"stream_start","w":1280,"h":720,"fmt":"h264","fps":30}""")
        // CR-029: `fps` es metadata (u8; 0 = unlimited) y ya no se valida en 1–60.
        val unlimited =
            StreamCodec.decode(
                """{"type":"stream_start","w":1280,"h":720,"fmt":"h264","fps":0,"media_port":8425}""",
            ) as StreamMessage.Start
        assertEquals(0, unlimited.fps)
        val maxMeta =
            StreamCodec.decode(
                """{"type":"stream_start","w":1280,"h":720,"fmt":"h264","fps":255,"media_port":8425}""",
            ) as StreamMessage.Start
        assertEquals(255, maxMeta.fps)
        assertInvalid("""{"type":"stream_start","w":1280,"h":720,"fmt":"h264","fps":-1,"media_port":8425}""")
        assertInvalid("""{"type":"stream_start","w":1280,"h":720,"fmt":"h264","fps":256,"media_port":8425}""")
        // SPEC-003 §4.1: `video_patch` no forma parte de la v1.5.0.
        assertInvalid("""{"type":"video_patch","fn":3,"base_fn":2,"w":64,"h":64,"tile_size":64,"patches":[]}""")
    }

    private fun assertInvalid(line: String) {
        try {
            StreamCodec.decode(line)
            throw AssertionError("invalid stream line should be rejected")
        } catch (expected: com.twinpad.android.app.core.PairingException) {
            // Expected strict protocol rejection.
        }
    }
}
