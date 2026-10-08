package com.twinpad.android.app.wire

/** Constantes del protocolo de sesión (SPEC-001 §4.2; ENG §5 P12). */
object ProtocolConstants {
    /** Bytes de nonces y proofs (base64url: 43 chars). */
    const val NONCE_BYTES: Int = 32

    /** Bytes del session_id (base64url: 22 chars). */
    const val SESSION_ID_BYTES: Int = 16

    /** Bytes del token del QR (SPEC-001 §4.1). */
    const val TOKEN_BYTES: Int = 32

    // Límites del stream de video (SPEC-003 §4).
    const val MIN_DIM: Int = 16
    const val MAX_WIDTH: Int = 3840
    const val MAX_HEIGHT: Int = 2160
    const val MIN_FPS: Int = 1
    const val MAX_FPS: Int = 60
    const val RGB_BYTES_PER_PIXEL: Int = 3
    const val GRAY_BYTES_PER_PIXEL: Int = 1

    /** Máscara de byte para convertir byte sin signo a valor RGB. */
    const val BYTE_MASK: Int = 0xFF

    /** Periodo del chequeo de frescura del stream (ms). */
    const val STREAM_TICK_MS: Long = 500L

    /** Sin frames en este lapso → V2 Interrupted (SPEC-003 §6). */
    const val STREAM_IDLE_TIMEOUT_MS: Long = 2_000L
}
