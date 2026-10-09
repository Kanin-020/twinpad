package com.twinpad.android.app.stream

/**
 * Estado del stream en el Android — V0/V1/V2 de SPEC-003 §6.
 */
enum class StreamState {
    /** Sin stream: la vista muestra "sin segunda pantalla activa" (AC-3). */
    INACTIVE,

    /** Llegan frames: presentar el Frame activo. */
    STREAMING,

    /** Sin frames en ≥ 2 s o corte; esperando recuperación (§8.2). */
    INTERRUPTED,
}

/**
 * Estado observable de la vista de Segunda pantalla.
 */
data class StreamUiState(
    val state: StreamState = StreamState.INACTIVE,
    /** Último frame válido decodificado (ARGB), listo para render. */
    val lastFrame: Any? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Máquina del stream (SPEC-003 §6/§10): orden exclusivo por `fn` (R2),
 * frames corruptos/tardíos descartados sin tocar el Frame activo, y
 * transiciones V0↔V1↔V2 según lleguen mensajes o expiren tiempos.
 */
class StreamStateMachine {
    var state: StreamState = StreamState.INACTIVE
        private set

    /** Último `fn` aceptado; `null` = nada aceptado en este stream. */
    var lastFrameNumber: Long? = null
        private set

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** Última actualización del Frame activo (ms del reloj del sistema). */
    private var lastFrameAtMs: Long = 0

    /**
     * Procesa una línea NDJSON del socket (ya fuera del Handshake).
     * Devuelve el bitmap ARGB a presentar, o `null` si nada cambió en la
     * vista (frame descartado, mensaje de control, etc.).
     */
    fun onLine(line: String): Any? {
        val msg =
            try {
                com.twinpad.android.app.wire.StreamCodec.decode(line)
            } catch (e: com.twinpad.android.app.core.PairingException) {
                return null // §9: frame/mensaje inválido se descarta
            }
        return handleMessage(msg)
    }

    private fun handleMessage(msg: com.twinpad.android.app.wire.StreamMessage): Any? =
        when (msg) {
            is com.twinpad.android.app.wire.StreamMessage.Start -> onStart(msg)
            is com.twinpad.android.app.wire.StreamMessage.Frame -> onFrame(msg)
            is com.twinpad.android.app.wire.StreamMessage.Stop -> {
                reset()
                null
            }
        }

    private fun onStart(msg: com.twinpad.android.app.wire.StreamMessage.Start): Any? {
        width = msg.width
        height = msg.height
        lastFrameNumber = null // §8.1: `fn` continúa, pero el render
        // se re-encuadra; aceptamos frames desde el próximo recibido.
        state = StreamState.STREAMING
        lastFrameAtMs = System.currentTimeMillis()
        return null
    }

    private fun onFrame(msg: com.twinpad.android.app.wire.StreamMessage.Frame): Any? {
        val last = lastFrameNumber
        val stale = last != null && msg.frameNumber <= last // §10: idempotencia
        // Los bytes ya vienen decodificados por el codec (UN base64).
        val bitmap =
            if (!stale) FrameRenderer.decode(msg.dataBytes, msg.width, msg.height, msg.fmt) else null
        if (bitmap == null) {
            return null // frame inconsistente descartado; se mantiene el activo
        }
        lastFrameNumber = msg.frameNumber
        width = msg.width
        height = msg.height
        state = StreamState.STREAMING
        lastFrameAtMs = System.currentTimeMillis()
        return bitmap
    }

    /** Registra un frame aceptado que ya fue decodificado fuera de la
     * máquina (pipeline, R2: `fn` monotónico). Devuelve `false` si era
     * tardío (el llamante lo descarta). */
    fun acceptFrame(
        frameNumber: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): Boolean {
        val last = lastFrameNumber
        if (last != null && frameNumber <= last) return false // §10
        lastFrameNumber = frameNumber
        width = frameWidth
        height = frameHeight
        state = StreamState.STREAMING
        lastFrameAtMs = System.currentTimeMillis()
        return true
    }

    /** Marca el stream interrumpido si no hay frames frescos (§6 V1→V2). */
    fun tick(
        nowMs: Long,
        streamingTimeoutMs: Long = 2_000,
    ): StreamState {
        if (state == StreamState.STREAMING && nowMs - lastFrameAtMs > streamingTimeoutMs) {
            state = StreamState.INTERRUPTED
        }
        return state
    }

    /** Vuelve a V0 (§6: `stream_stop` o fin de sesión). */
    fun reset() {
        state = StreamState.INACTIVE
        lastFrameNumber = null
        width = 0
        height = 0
        lastFrameAtMs = 0
    }
}
