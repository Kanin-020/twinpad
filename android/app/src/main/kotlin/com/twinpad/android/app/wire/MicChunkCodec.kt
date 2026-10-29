package com.twinpad.android.app.wire

import java.util.Base64

/**
 * Codificador de `mic_chunk` (SPEC-006 §4, CR-035). PCM s16le mono a 32 kHz
 * (la cadencia exacta que consume el HLE `mic` de Cemu: 96 muestras por
 * frame de 3 ms); `data` es base64url sin relleno de exactamente
 * 2·`samples` bytes.
 */
object MicChunkCodec {
    const val SAMPLE_RATE: Int = 32000
    const val MIN_SAMPLES: Int = 32
    const val MAX_SAMPLES: Int = 32000

    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8

    /**
     * Una línea NDJSON canónica `mic_chunk`. Rechaza fragmentos incoherentes
     * con el contrato (fuera del rango [MIN, MAX] muestras) antes de serializar.
     */
    fun encode(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): String {
        require(count in MIN_SAMPLES..MAX_SAMPLES) { "mic_chunk fuera de rango: count=$count" }
        require(offset >= 0 && offset + count <= samples.size) {
            "mic_chunk fragmento incoherente: offset=$offset count=$count size=${samples.size}"
        }
        val pcm = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = samples[offset + i]
            pcm[i * 2] = (value.toInt() and BYTE_MASK).toByte()
            pcm[i * 2 + 1] = ((value.toInt() shr BYTE_SHIFT) and BYTE_MASK).toByte()
        }
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(pcm)
        return "{\"type\":\"mic_chunk\",\"samples\":$count,\"data\":\"$data\"}"
    }
}
