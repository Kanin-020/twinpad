package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** TEST-0104 — verifies SPEC-006 (CR-035): canonical `mic_chunk` encoding (s16le
 * mono 32 kHz, 2·samples bytes base64url) and strict `mic_request` decoding. */
class MicChunkCodecTest {
    private fun decodeData(data: String): ByteArray = Base64.getUrlDecoder().decode(data)

    /** `data` no contiene \" ni }, el cortado es inequívoco. */
    private fun payloadOf(line: String): String = line.substringAfter("\"data\":\"").substringBefore("\"}")

    @Test
    fun encodes_canonical_mic_chunk_with_little_endian_pcm() {
        val samples =
            shortArrayOf(
                1, -1, 0, 0x7FFF, -0x8000,
            ) + ShortArray(475) // 480 muestras = 15 ms @ 32 kHz
        val line = MicChunkCodec.encode(samples, 0, samples.size)
        assertTrue(line.startsWith("{\"type\":\"mic_chunk\",\"samples\":480,\"data\":\""))
        val pcm = decodeData(payloadOf(line)).also { assertEquals(960, it.size) }
        // s16le: sample 1 → 01 00; -1 → FF FF; 0x7FFF → FF 7F; -0x8000 → 00 80.
        assertEquals(0x01, pcm[0].toInt() and 0xFF)
        assertEquals(0x00, pcm[1].toInt() and 0xFF)
        assertEquals(0xFF, pcm[2].toInt() and 0xFF)
        assertEquals(0xFF, pcm[3].toInt() and 0xFF)
        assertEquals(0xFF, pcm[6].toInt() and 0xFF)
        assertEquals(0x7F, pcm[7].toInt() and 0xFF)
        assertEquals(0x00, pcm[8].toInt() and 0xFF)
        assertEquals(0x80, pcm[9].toInt() and 0xFF)
    }

    @Test
    fun encodes_from_an_offset_slice() {
        val samples = ShortArray(500)
        samples[20] = 5 // solo la ventana [20, 52) entra al chunk
        samples[MicChunkCodec.MIN_SAMPLES + 5] = 5 // fuera de la ventana
        val line = MicChunkCodec.encode(samples, 20, MicChunkCodec.MIN_SAMPLES)
        assertEquals(32, line.substringBefore(",\"data\"").substringAfterLast(':').toInt())
        assertEquals(5, decodeData(payloadOf(line))[0].toInt())
    }

    @Test
    fun rejects_fragments_outside_the_contract() {
        val ok = ShortArray(MicChunkCodec.MIN_SAMPLES)
        // Bajo el mínimo (1 ms) y por encima del máximo (1 s).
        assertThrows(IllegalArgumentException::class.java) {
            MicChunkCodec.encode(ok, 0, MicChunkCodec.MIN_SAMPLES - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MicChunkCodec.encode(ok, 0, MicChunkCodec.MAX_SAMPLES + 1)
        }
        // Fragmento que no cabe en la fuente.
        assertThrows(IllegalArgumentException::class.java) {
            MicChunkCodec.encode(ok, 0, MicChunkCodec.MIN_SAMPLES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MicChunkCodec.encode(ok, -1, MicChunkCodec.MIN_SAMPLES)
        }
    }

    @Test
    fun decodes_mic_request_strictly() {
        assertEquals(true, MicRequestCodec.decode("""{"type":"mic_request","on":true}""")!!.on)
        assertEquals(false, MicRequestCodec.decode("""{"type":"mic_request","on":false}""")!!.on)
        // Estricto: flag ausente, tipo distinto o JSON inválido → null.
        assertNull(MicRequestCodec.decode("""{"type":"mic_request"}"""))
        assertNull(MicRequestCodec.decode("""{"type":"camera_request","on":true}"""))
        assertNull(MicRequestCodec.decode("no-es-json"))
    }
}
