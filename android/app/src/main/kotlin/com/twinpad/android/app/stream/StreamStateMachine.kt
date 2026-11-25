package com.twinpad.android.app.stream

/**
 * Estado del stream en el Android — V0/V1/V2 de SPEC-003 §6.
 */
enum class StreamState {
    /** Sin stream: la vista muestra "sin segunda pantalla activa" (AC-3). */
    INACTIVE,

    /** Llegan AUs por el canal media: presentar el Frame activo. */
    STREAMING,

    /** Sin frames en ≥ 2 s o corte; esperando recuperación (§8.2). */
    INTERRUPTED,
}

/**
 * Estado observable de la vista de Segunda pantalla.
 */
data class StreamUiState(
    val state: StreamState = StreamState.INACTIVE,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Máquina del stream (SPEC-003 §6/§10): orden exclusivo por `fn` (R2) y
 * transiciones V0↔V1↔V2. Desde CR-014 el video H.264 llega por el canal
 * media, así que del socket de control solo entran `stream_start` y
 * `stream_stop`; los frames se registran con [acceptFrame].
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

    /** `stream_start` vigente (SPEC-003 §4.1): fija dimensiones y entra en V1. */
    fun onStreamStart(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        lastFrameNumber = null // §8.1: `fn` continúa, pero el render
        // se re-encuadra; aceptamos frames desde el próximo recibido.
        state = StreamState.STREAMING
        lastFrameAtMs = System.currentTimeMillis()
    }

    /** `stream_stop` (§4.1): vuelve a V0 y suelta el Frame activo. */
    fun onStreamStop() = reset()

    /** Registra un frame aceptado que ya fue decodificado por MediaCodec
     * (R2: `fn` monotónico). Devuelve `false` si era tardío (el llamante lo
     * descarta). */
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
