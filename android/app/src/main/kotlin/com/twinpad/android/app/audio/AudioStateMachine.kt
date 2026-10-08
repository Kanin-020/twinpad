package com.twinpad.android.app.audio

/**
 * Estados del stream de audio en el Android — A0/A1/A2 de SPEC-005 §6.
 */
enum class AudioState {
    /** Sin audio: silencio (§5). */
    INACTIVE,

    /** Chunks llegando y reproduciéndose (§6 A1). */
    PLAYING,

    /** Sin chunks dentro del jitter buffer ≥ 500 ms (§6 A2). */
    INTERRUPTED,
}

/**
 * Fragmento de PCM listo para reproducir (s16le, parámetros de `audio_start`).
 */
data class AudioChunk(
    val seq: Long,
    /** PCM s16le intercalado, `samples × channels × 2` bytes. */
    val pcm: ByteArray,
    val rate: Int,
    val channels: Int,
)

/**
 * Máquina del stream de audio (SPEC-005 §6/§9/§10), lado app:
 * - Orden exclusivo por `seq` (R2); chunks con `seq` ≤ último descartados.
 * - Fragmento con `data` inconsistente descartado; la reproducción continúa (§9).
 * - Chunks con parámetros distintos al `audio_start` vigente: descartados (§10).
 * - A1→A2 sin chunks ≥ 500 ms (jitter buffer fijo, OQ §17); A2→A1 con el
 *   siguiente chunk (reanudación sin re-pairing, §8.2).
 *
 * Sin imports de Android: testeable en JVM (ENG §5 P11). La reproducción
 * real (AudioTrack) es un adaptador que consume los fragmentos drenados.
 */
class AudioStateMachine(
    /** Decodificador base64url inyectable (JVM vs android.util.Base64). */
    private val base64: (String) -> ByteArray =
        { data ->
            java.util.Base64.getUrlDecoder().decode(data)
        },
) {
    var state: AudioState = AudioState.INACTIVE
        private set

    var rate: Int = 0
        private set
    var channels: Int = 0
        private set

    /** Última actualización del fragmento activo (ms del reloj del sistema). */
    private var lastChunkAtMs: Long = 0

    private var lastSeq: Long? = null

    /** Fragmentos aceptados y en orden, listos para el adaptador de salida. */
    private val pending = ArrayDeque<AudioChunk>()

    /** Fragmento(s) aceptados desde la última consulta (para AudioTrack). */
    fun drainPending(): List<AudioChunk> {
        val out = pending.toList()
        pending.clear()
        return out
    }

    /**
     * Procesa una línea NDJSON de la fase de sesión. Devuelve `true` si el
     * estado observable cambió (para actualizar la vista); los chunks se
     * recuperan con [drainPending].
     */
    fun onLine(
        line: String,
        nowMs: Long,
    ): Boolean {
        val msg =
            try {
                com.twinpad.android.app.wire.AudioCodec.decode(line)
            } catch (e: Exception) {
                return false // §9: fragmento/mensaje inválido se descarta
            }
        return when (msg) {
            is com.twinpad.android.app.wire.AudioMessage.Start -> onStart(msg, nowMs)
            is com.twinpad.android.app.wire.AudioMessage.Chunk -> onChunk(msg, nowMs)
            is com.twinpad.android.app.wire.AudioMessage.Stop -> {
                reset()
                true
            }
        }
    }

    private fun onStart(
        msg: com.twinpad.android.app.wire.AudioMessage.Start,
        nowMs: Long,
    ): Boolean {
        // §10: re-anuncio con parámetros nuevos → re-configura la salida.
        rate = msg.rate
        channels = msg.channels
        lastSeq = null
        pending.clear()
        state = AudioState.PLAYING
        lastChunkAtMs = nowMs
        return true
    }

    private fun onChunk(
        msg: com.twinpad.android.app.wire.AudioMessage.Chunk,
        nowMs: Long,
    ): Boolean {
        val pcm = validChunkPcm(msg) ?: return false
        lastSeq = msg.seq
        pending.addLast(AudioChunk(msg.seq, pcm, rate, channels))
        state = AudioState.PLAYING
        lastChunkAtMs = nowMs
        return true
    }

    /** PCM del chunk si pasa §9/§10/§14, o `null` para descartarlo. */
    private fun validChunkPcm(msg: com.twinpad.android.app.wire.AudioMessage.Chunk): ByteArray? {
        val inOrder =
            state != AudioState.INACTIVE &&
                (lastSeq == null || msg.seq > (lastSeq ?: 0))
        val pcm = if (inOrder) tryDecode(msg.data) else null
        val sized =
            pcm != null && pcm.size % 2 == 0 &&
                pcm.size <= com.twinpad.android.app.wire.ProtocolConstants.AUDIO_CHUNK_MAX_BYTES
        return if (sized) pcm else null
    }

    /** base64url del fragmento, o `null` si es corrupto (§9). */
    private fun tryDecode(data: String): ByteArray? =
        try {
            decodeBase64(data)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** A1→A2: sin chunks en ≥ 500 ms (jitter buffer, §6). */
    fun tick(nowMs: Long): AudioState {
        if (state == AudioState.PLAYING && nowMs - lastChunkAtMs > JITTER_BUFFER_MS) {
            state = AudioState.INTERRUPTED
        }
        return state
    }

    /** Vuelve a A0 (§6: `audio_stop` o fin de sesión). */
    fun reset() {
        state = AudioState.INACTIVE
        lastSeq = null
        rate = 0
        channels = 0
        pending.clear()
        lastChunkAtMs = 0
    }

    private fun decodeBase64(data: String): ByteArray = base64(data)

    companion object {
        /** Jitter buffer fijo (SPEC-005 §6; OQ §17 resuelta: no ajustable). */
        const val JITTER_BUFFER_MS: Long = 500L
    }
}
