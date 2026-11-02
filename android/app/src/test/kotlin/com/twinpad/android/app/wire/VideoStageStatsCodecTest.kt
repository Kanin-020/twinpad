package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-0117 — verifies SPEC-006/DES-004 §7.4 (CR-053): `video_stage_stats` decoding and HUD formatting are strict. */
class VideoStageStatsCodecTest {
    private val validLine =
        """{"type":"video_stage_stats","window_ms":5000,""" +
            """"stages":[{"stage":"pixel_convert","count":120,"avg_us":210,"max_us":480},""" +
            """{"stage":"base64_json","count":120,"avg_us":55,"max_us":90}],""" +
            """"events":[{"event":"frame_encoded","count":120}]}"""

    @Test
    fun decodes_valid_window() {
        val metrics = VideoStageStatsCodec.decode(validLine)
        assertEquals(5000, metrics?.windowMs)
        assertEquals(2, metrics?.stages?.size)
        assertEquals(VideoStageStatMessage("pixel_convert", 120, 210, 480), metrics?.stages?.first())
        assertEquals(listOf(VideoStageEventMessage("frame_encoded", 120)), metrics?.events)
    }

    @Test
    fun rejects_lines_outside_the_contract() {
        // type distinto.
        assertNull(VideoStageStatsCodec.decode(validLine.replace("video_stage_stats", "video_stage_stats_x")))
        // Ventana fuera de rango.
        assertNull(VideoStageStatsCodec.decode(validLine.replace("5000", "4")))
        assertNull(VideoStageStatsCodec.decode(validLine.replace("5000", "20000")))
        // Etapa fuera del catálogo.
        assertNull(VideoStageStatsCodec.decode(validLine.replace("pixel_convert", "pixel_free")))
        // avg_us > max_us.
        assertNull(VideoStageStatsCodec.decode(validLine.replace("avg_us\":210", "avg_us\":481")))
        // count < 1.
        assertNull(VideoStageStatsCodec.decode(validLine.replace("count\":120", "count\":0")))
        // Tupla sin count de etapa.
        assertNull(
            VideoStageStatsCodec.decode(
                """{"type":"video_stage_stats","window_ms":5000,""" +
                    """"stages":[{"stage":"scale","avg_us":1,"max_us":2}],"events":[]}"""",
            ),
        )
        // Etapa repetida.
        assertNull(
            VideoStageStatsCodec.decode(
                """{"type":"video_stage_stats","window_ms":5000,""" +
                    """"stages":[{"stage":"scale","count":1,"avg_us":1,"max_us":2},""" +
                    """{"stage":"scale","count":1,"avg_us":1,"max_us":2}],"events":[]}""",
            ),
        )
        // Evento fuera del catálogo.
        val badEventLine =
            """{"type":"video_stage_stats","window_ms":5000,"stages":[],""" +
                """"events":[{"event":"frame_x","count":1}]}"""
        assertNull(VideoStageStatsCodec.decode(badEventLine))
        // Línea no JSON.
        assertNull(VideoStageStatsCodec.decode("not json"))
    }

    @Test
    fun formats_compact_stage_line() {
        val metrics = VideoStageStatsCodec.decode(validLine)!!
        val text = VideoStageStatsCodec.format(metrics)!!
        assertFalse(text.isEmpty())
        // Rótulos cortos con promedio (máximo), separados por «·».
        assertEquals("pix 210 µs (480 µs) · b64 55 µs (90 µs) · 120 ev", text.normalizeSpaces())
    }

    private fun String.normalizeSpaces(): String = replace('\u00A0', ' ')

    // 55 µs → «55 µs» según la unidad mínima del formateador.
    @Test
    fun formats_null_without_content() {
        val emptyLine =
            """{"type":"video_stage_stats","window_ms":5000,"stages":[],"events":[]}"""
        val empty = VideoStageStatsCodec.decode(emptyLine)!!
        assertNull(VideoStageStatsCodec.format(empty))
    }
}
