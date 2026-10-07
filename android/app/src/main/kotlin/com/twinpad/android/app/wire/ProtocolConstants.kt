package com.twinpad.android.app.wire

/** Constantes del protocolo de sesión (SPEC-001 §4.2; ENG §5 P12). */
object ProtocolConstants {
    /** Bytes de nonces y proofs (base64url: 43 chars). */
    const val NONCE_BYTES: Int = 32

    /** Bytes del session_id (base64url: 22 chars). */
    const val SESSION_ID_BYTES: Int = 16

    /** Bytes del token del QR (SPEC-001 §4.1). */
    const val TOKEN_BYTES: Int = 32
}
