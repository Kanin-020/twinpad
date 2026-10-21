package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-025 — verifies SPEC-003 §4: frame ordering metadata is read without decoding payload bytes. */
class StreamCodecTest {
    @Test
    fun frame_number_fast_path_reads_canonical_frame_and_rejects_invalid_values() {
        assertEquals(
            42L,
            StreamCodec.frameNumber(
                """{"type":"video_frame","fn":42,"ts":1,"w":16,"h":16,"fmt":"jpeg","data":"large-payload"}""",
            ),
        )
        assertNull(StreamCodec.frameNumber("""{"type":"video_frame","fn":"bad"}"""))
        assertNull(StreamCodec.frameNumber("""{"type":"video_frame"}"""))
    }

    // TEST-027 — verifies SPEC-003/CR-002: strict patch parsing and tile geometry.
    @Test
    fun patch_codec_accepts_valid_edge_tile_and_rejects_bad_base_or_duplicates() {
        val validTile =
            """{"x":64,"y":64,"w":16,"h":6,"fmt":"jpeg","data":"_9j_2Q"}"""
        val valid =
            """{"type":"video_patch","fn":2,"base_fn":1,"ts":1,"w":80,"h":70,"tile_size":64,"patches":[$validTile]}"""
        val patch = StreamCodec.decode(valid) as StreamMessage.Patch
        assertEquals(1, patch.tiles.size)
        assertEquals(16, patch.tiles[0].width)
        assertEquals(6, patch.tiles[0].height)
        val duplicate = valid.replace("[$validTile]", "[$validTile,$validTile]")
        assertInvalid(duplicate)
        assertInvalid(valid.replace("\"base_fn\":1", "\"base_fn\":2"))
    }

    // TEST-074 — verifies SPEC-003/CR-014: H.264 start contract and fail-closed parsing.
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
