package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Máximo de bytes de un patrón de vibración (CR-033; paridad con `core::game`). */
const val RUMBLE_MAX_BYTES: Int = 120

/** Un bit del patrón dura 16 ms: `VPADControlMotor` avanza a 60 Hz (CR-033). */
const val RUMBLE_BIT_MS: Long = 16L

/** Mensaje `rumble` (CR-033). `pattern` vacío = parar. */
data class RumbleMessage(val pattern: ByteArray) {
    override fun equals(other: Any?): Boolean = other is RumbleMessage && other.pattern.contentEquals(pattern)

    override fun hashCode(): Int = pattern.contentHashCode()
}

/** Forma de onda para `VibrationEffect.createWaveform`: tiempos y amplitudes por bit. */
data class RumbleWaveform(val timings: LongArray, val amplitudes: IntArray)

/**
 * Decodificador de `rumble` (CR-033) y traducción del patrón. Estricto: campo
 * faltante, `length` fuera de rango o `data` que no mide `length` bytes → null.
 */
object RumbleCodec {
    private const val ON_AMPLITUDE: Int = 255
    private const val OFF_AMPLITUDE: Int = 0
    private const val BYTE_BITS: Int = 8
    private const val BYTE_MASK: Int = 0xFF
    private const val PAIR_STEP: Int = 2
    private const val PAIR_MASK: Int = 3

    /** Decodifica la línea; null si no es un `rumble` válido. */
    fun decode(line: String): RumbleMessage? {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (obj?.get("type")?.jsonPrimitive?.content != "rumble") return null
        val length = obj["length"]?.jsonPrimitive?.content?.toIntOrNull()
        val bytes = obj["data"]?.jsonPrimitive?.content?.let(::decodeBase64)
        return toMessage(length, bytes)
    }

    private fun toMessage(
        length: Int?,
        bytes: ByteArray?,
    ): RumbleMessage? {
        if (length == null || bytes == null) return null
        return if (length in 0..RUMBLE_MAX_BYTES && bytes.size == length) RumbleMessage(bytes) else null
    }

    private fun decodeBase64(data: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(data) }.getOrNull()

    /**
     * Replica el bitset de `VPADController::push_rumble`: por cada byte toma los
     * bits pares j = 0, 2, 4, 6 mientras `j < restante`; `restante` empieza en
     * `length` y baja 8 por byte. Es el mismo bucle de Cemu, con su comparación
     * de bits contra la longitud en bytes.
     */
    fun bits(pattern: ByteArray): List<Boolean> {
        val out = ArrayList<Boolean>()
        var remaining = pattern.size
        for (byte in pattern) {
            val p = byte.toInt() and BYTE_MASK
            var j = 0
            while (j < BYTE_BITS && j < remaining) {
                out.add((p and (PAIR_MASK shl j)) != 0)
                j += PAIR_STEP
            }
            remaining -= BYTE_BITS
        }
        return out
    }

    /** Un bit encendido vibra a la amplitud máxima; uno apagado queda en silencio. */
    fun waveform(pattern: ByteArray): RumbleWaveform {
        val bits = bits(pattern)
        return RumbleWaveform(
            timings = LongArray(bits.size) { RUMBLE_BIT_MS },
            amplitudes = IntArray(bits.size) { if (bits[it]) ON_AMPLITUDE else OFF_AMPLITUDE },
        )
    }
}
