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

    // TEST-0153 — verifies CR-075: `video_stage_stats` optional details
    // (hardware encoder, input/output resolutions) decode and HUD detail line.
    @Test
    fun decodes_hw_and_input_output_details_when_present() {
        val line =
            """{"type":"video_stage_stats","window_ms":5000,""" +
                """"hw":true,"in_w":854,"in_h":480,""" +
                """"out_w":1280,"out_h":720,""" +
                """"stages":[],"events":[]}"""
        val metrics = VideoStageStatsCodec.decode(line)
        assertEquals(true, metrics?.hardwareEncoder)
        assertEquals(854, metrics?.inputWidth)
        assertEquals(480, metrics?.inputHeight)
        assertEquals(1280, metrics?.outputWidth)
        assertEquals(720, metrics?.outputHeight)
    }

    @Test
    fun leaves_details_null_for_legacy_line_without_them() {
        val metrics = VideoStageStatsCodec.decode(validLine)
        assertEquals(null, metrics?.hardwareEncoder)
        assertEquals(null, metrics?.inputWidth)
        assertEquals(null, metrics?.inputHeight)
        assertEquals(null, metrics?.outputWidth)
        assertEquals(null, metrics?.outputHeight)
    }

    @Test
    fun invalid_or_partial_details_do_not_kill_the_line() {
        val base =
            """{"type":"video_stage_stats","window_ms":5000,""" +
                """"stages":[],"events":[]}"""
        // hw no booleano, dims parciales y fuera de rango: se ignoran.
        val extra =
            """"window_ms":5000,"hw":1,"in_w":854,"out_h":999999,"""
        val bad = base.replace(""""window_ms":5000,"""", extra)
        val metrics = VideoStageStatsCodec.decode(bad)
        assertEquals(null, metrics?.hardwareEncoder)
        assertEquals(null, metrics?.inputWidth)
        assertEquals(null, metrics?.outputWidth)
    }

    @Test
    fun formats_details_line_with_only_known_data() {
        val line =
            """{"type":"video_stage_stats","window_ms":5000,""" +
                """"hw":false,"in_w":854,"in_h":480,""" +
                """"out_w":854,"out_h":480,""" +
                """"stages":[],"events":[]}"""
        val metrics = VideoStageStatsCodec.decode(line)!!
        val fullDetails = VideoStageStatsCodec.formatDetails(metrics, "UDP")
        assertEquals("HW: no · Trans: UDP · E: 854×480 · S: 854×480", fullDetails)
        // Sin transporte decidido: omite el rótulo «Trans» (hw sí está).
        val noTransport = VideoStageStatsCodec.formatDetails(metrics)
        assertEquals("HW: no · E: 854×480 · S: 854×480", noTransport)
        // Sin nada conocido: null (HUD oculta la línea).
        val legacy = VideoStageStatsMessage(5000, emptyList(), emptyList())
        assertNull(VideoStageStatsCodec.formatDetails(legacy))
    }

    // 55 µs → «55 µs» según la unidad mínima del formateador.
    @Test
    fun formats_null_without_content() {
        val emptyLine =
            """{"type":"video_stage_stats","window_ms":5000,"stages":[],"events":[]}"""
        val empty = VideoStageStatsCodec.decode(emptyLine)!!
        assertNull(VideoStageStatsCodec.format(empty))
    }
}
