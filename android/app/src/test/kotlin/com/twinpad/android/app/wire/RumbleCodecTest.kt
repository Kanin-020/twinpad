package com.twinpad.android.app.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** TEST-092 — verifies SPEC-006 (CR-033): rumble decoding and VPADController bit translation. */
class RumbleCodecTest {
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun decodes_a_valid_pattern_and_an_empty_stop() {
        val pattern = byteArrayOf(0x03, 0x00, 0x0C)
        val line = """{"type":"rumble","length":3,"data":"${b64(pattern)}"}"""
        assertArrayEquals(pattern, RumbleCodec.decode(line)!!.pattern)

        val stop = RumbleCodec.decode("""{"type":"rumble","length":0,"data":""}""")
        assertTrue(stop!!.pattern.isEmpty())
    }

    @Test
    fun rejects_length_mismatch_bad_base64_oversized_and_other_types() {
        assertNull(RumbleCodec.decode("""{"type":"rumble","length":2,"data":"${b64(byteArrayOf(1, 2, 3))}"}"""))
        assertNull(RumbleCodec.decode("""{"type":"rumble","length":1,"data":"@@@"}"""))
        val big = ByteArray(121)
        assertNull(RumbleCodec.decode("""{"type":"rumble","length":121,"data":"${b64(big)}"}"""))
        assertNull(RumbleCodec.decode("""{"type":"audio_stop","reason":"plugin_stopped"}"""))
    }

    @Test
    fun bits_follow_the_vpad_loop_even_bit_positions_per_byte() {
        // 16 bytes: byte0=0x03 -> [on,off,off,off]; byte1=0x30 -> [off,off,on,off];
        // el resto de bytes queda fuera porque el restante llega a 0 tras 2 bytes.
        val pattern = ByteArray(16)
        pattern[0] = 0x03
        pattern[1] = 0x30
        assertEquals(
            listOf(true, false, false, false, false, false, true, false),
            RumbleCodec.bits(pattern),
        )
    }

    @Test
    fun waveform_uses_16_ms_per_bit_and_full_or_zero_amplitude() {
        val pattern = ByteArray(16)
        pattern[0] = 0x03
        pattern[1] = 0x30
        val wave = RumbleCodec.waveform(pattern)
        assertEquals(8, wave.timings.size)
        assertTrue(wave.timings.all { it == 16L })
        assertEquals(listOf(255, 0, 0, 0, 0, 0, 255, 0), wave.amplitudes.toList())
    }
}
