package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-0157 — verifies SPEC-006 (CR-076 v2): decodificación estricta de
 * `host_metrics` (server→app), campos omitibles (gpu/fps) y rechazos. */
class HostMetricsCodecTest {
    @Test
    fun decodes_the_canonical_message_with_gpu_and_fps() {
        val line =
            """{"type":"host_metrics","cpu_percent":37,"gpu_percent":41,""" +
                """"ram_used_mb":10612,"ram_total_mb":15729,"game_fps":59.8}"""
        assertEquals(
            HostMetrics(37, 41, 10612, 15729, 59.8f),
            HostMetricsCodec.decode(line),
        )
    }

    @Test
    fun gpu_and_fps_are_omittable_and_integer_fps_is_accepted() {
        val line = """{"type":"host_metrics","cpu_percent":0,"ram_used_mb":0,"ram_total_mb":1}"""
        assertEquals(HostMetrics(0, null, 0, 1, null), HostMetricsCodec.decode(line))
        // fps entero (60) también vale.
        val line60 =
            """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":0,""" +
                """"ram_total_mb":1,"game_fps":60}"""
        assertEquals(60f, HostMetricsCodec.decode(line60)!!.gameFps)
    }

    @Test
    fun rejects_out_of_range_percent_and_incoherent_ram() {
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":101,"ram_used_mb":0,"ram_total_mb":1}""",
            ),
        )
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":5,"gpu_percent":137,""" +
                    """"ram_used_mb":0,"ram_total_mb":1}""",
            ),
        )
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":2,"ram_total_mb":1}""",
            ),
        )
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":0,"ram_total_mb":0}""",
            ),
        )
    }

    @Test
    fun rejects_negative_fps_bad_type_and_malformed() {
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":0,""" +
                    """"ram_total_mb":1,"game_fps":-1.0}""",
            ),
        )
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":-5,"ram_used_mb":0,"ram_total_mb":1}""",
            ),
        )
        assertNull(HostMetricsCodec.decode("""{"type":"audio_stop","reason":"plugin_stopped"}"""))
        assertNull(HostMetricsCodec.decode("no json"))
    }

    /** TEST-0177 — CR-085: temps CPU/GPU del hwmon y fan del GPU son
     * omitibles («sin dato» → null) y el fan fuera de 0..100 se rechaza
     * (misma semántica del gpu_percent horse del sobre). */
    @Test
    fun temp_and_fan_fields_are_omittable_and_bounded() {
        val line =
            """{"type":"host_metrics","cpu_percent":12,""" +
                """"ram_used_mb":512,"ram_total_mb":15729,""" +
                """"cpu_temp_c":58,"gpu_temp_c":64,"fan_percent":37}"""
        assertEquals(
            HostMetrics(12, null, 512, 15729, null, 58, 64, 37),
            HostMetricsCodec.decode(line),
        )

        // Sin los campos nuevos (host v1): los tres quedan null.
        val legacy = """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":0,"ram_total_mb":1}"""
        assertEquals(
            HostMetrics(5, null, 0, 1, null, null, null, null),
            HostMetricsCodec.decode(legacy),
        )

        // fan sobre 100: línea inválida.
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":0,""" +
                    """"ram_total_mb":1,"fan_percent":101}""",
            ),
        )
        // temp presente pero no numérica: línea inválida (guarda del sobre).
        assertNull(
            HostMetricsCodec.decode(
                """{"type":"host_metrics","cpu_percent":5,"ram_used_mb":0,""" +
                    """"ram_total_mb":1,"cpu_temp_c":"asi"}""",
            ),
        )
    }
}
