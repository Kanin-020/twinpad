package com.twinpad.android.app.pairing

/** Session cryptographic context shared by media handshake helpers. */
internal data class MediaHandshakeContext(
    val sessionId: ByteArray,
    val sessionKey: ByteArray,
    val clientNonce: ByteArray,
    val serverNonce: ByteArray,
    val mediaKey: ByteArray,
)
