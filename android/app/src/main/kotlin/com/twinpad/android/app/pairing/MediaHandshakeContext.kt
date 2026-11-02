package com.twinpad.android.app.pairing

/** Contexto criptográfico de media vigente durante el handshake (SPEC-003):
 * IDs/nonces en bytes crudos y `media_key` ya derivada; identidad por
 * contenido de ByteArray no aplica (transporte interno de un shot). */
internal data class MediaHandshakeContext(
    /** `session_id` crudo del handshake de Pairing (SPEC-001 §7). */
    val sessionId: ByteArray,
    /** `session_key` vigente (HKDF del token, SPEC-001 R4). */
    val sessionKey: ByteArray,
    /** `nonce_c` propio (SPEC-001 §7). */
    val clientNonce: ByteArray,
    /** `nonce_s` del server (SPEC-001 §7). */
    val serverNonce: ByteArray,
    /** `media_key` del canal TCP ya derivada (MediaCrypto.deriveMediaKey). */
    val mediaKey: ByteArray,
)
