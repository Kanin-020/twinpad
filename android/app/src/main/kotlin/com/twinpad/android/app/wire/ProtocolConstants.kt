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

    // Signature HID (SPEC-004 §2/§4; transporte inicial ADR-007).
    const val STICK_MIN: Int = -127
    const val STICK_MAX: Int = 127
    const val TRIGGER_MAX: Int = 255
    const val DPAD_NEUTRAL: Int = 8
    const val BUTTON_COUNT: Int = 12
    const val BUTTON_A_BIT: Int = 0

    /** Direcciones del D-pad (SPEC-004 §4: 0–7 en sentido horario desde el norte). */
    const val DPAD_UP: Int = 0
    const val DPAD_RIGHT: Int = 2
    const val DPAD_DOWN: Int = 4
    const val DPAD_LEFT: Int = 6

    /** Máscaras de bit de los botones A/B/X/Y (SPEC-004 §4: bit 0 = button_a). */
    const val BUTTON_BIT_A: Int = 1 shl 0
    const val BUTTON_BIT_B: Int = 1 shl 1
    const val BUTTON_BIT_X: Int = 1 shl 2
    const val BUTTON_BIT_Y: Int = 1 shl 3

    /** Ejes del giroscopio opcional (SPEC-004 §4). */
    const val GYRO_AXES: Int = 3

    // Detección de juegos (SPEC-006 §4; transporte ADR-008).
    const val GAME_ID_MAX: Int = 64
    const val GAME_NAME_MAX: Int = 128
}
