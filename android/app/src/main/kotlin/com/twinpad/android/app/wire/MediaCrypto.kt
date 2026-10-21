package com.twinpad.android.app.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives for the authenticated H.264 media channel (SPEC-003 §4.2–4.3). */
object MediaCrypto {
    const val PORT = 8425
    const val MAX_RECORD_BYTES = 4 * 1024 * 1024
    const val TAG_BYTES = 16
    const val SESSION_KEY_BYTES = 32
    const val SESSION_ID_BYTES = 16
    const val NONCE_BYTES = 32
    private const val LENGTH_BYTES = 4
    private const val COUNTER_BYTES = 8
    private const val RECORD_HEADER_BYTES = LENGTH_BYTES + COUNTER_BYTES
    private const val GCM_TAG_BITS = TAG_BYTES * 8
    private const val GCM_NONCE_BYTES = 12
    private const val HKDF_FIRST_BLOCK = 1
    private const val UINT32_MAX_AS_LONG = 0xffff_ffffL
    private const val ZERO_NONCE_PREFIX = 0
    private const val MIN_RECORD_BYTES = COUNTER_BYTES + TAG_BYTES
    private const val AUTHENTICATED_HEADER_BYTES = RECORD_HEADER_BYTES
    private const val CIPHERTEXT_OFFSET = RECORD_HEADER_BYTES
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val CLIENT_CONTEXT = "twinpad.v1 media client".toByteArray(Charsets.US_ASCII)
    private val SERVER_CONTEXT = "twinpad.v1 media server".toByteArray(Charsets.US_ASCII)
    private val MEDIA_INFO = "twinpad.v1 media server-to-client aes-256-gcm".toByteArray(Charsets.US_ASCII)

    fun clientProof(
        sessionKey: ByteArray,
        sessionId: ByteArray,
        nonceC: ByteArray,
    ): ByteArray {
        require(sessionKey.size == SESSION_KEY_BYTES && sessionId.size == SESSION_ID_BYTES)
        require(nonceC.size == NONCE_BYTES)
        return hmac(sessionKey, CLIENT_CONTEXT, sessionId, nonceC)
    }

    fun serverProof(
        sessionKey: ByteArray,
        sessionId: ByteArray,
        nonceC: ByteArray,
        nonceS: ByteArray,
    ): ByteArray {
        require(
            sessionKey.size == SESSION_KEY_BYTES && sessionId.size == SESSION_ID_BYTES &&
                nonceC.size == NONCE_BYTES && nonceS.size == NONCE_BYTES,
        )
        return hmac(sessionKey, SERVER_CONTEXT, sessionId, nonceC, nonceS)
    }

    fun verify(
        expected: ByteArray,
        actual: ByteArray,
    ): Boolean = expected.size == actual.size && MessageDigest.isEqual(expected, actual)

    fun deriveMediaKey(
        sessionKey: ByteArray,
        nonceC: ByteArray,
        nonceS: ByteArray,
    ): ByteArray {
        require(sessionKey.size == SESSION_KEY_BYTES && nonceC.size == NONCE_BYTES && nonceS.size == NONCE_BYTES)
        val salt = nonceC + nonceS
        val prk = hmac(salt, sessionKey)
        return hmac(prk, MEDIA_INFO, byteArrayOf(HKDF_FIRST_BLOCK.toByte()))
    }

    /** Parses and authenticates a full wire record, requiring the exact next counter. */
    fun openRecord(
        key: ByteArray,
        expectedCounter: Long,
        record: ByteArray,
    ): ByteArray {
        require(key.size == SESSION_KEY_BYTES && record.size >= LENGTH_BYTES + MIN_RECORD_BYTES)
        val header = ByteBuffer.wrap(record, 0, RECORD_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        val length = header.int.toLong() and UINT32_MAX_AS_LONG
        val counter = header.long
        require(length in MIN_RECORD_BYTES.toLong()..MAX_RECORD_BYTES.toLong())
        require(record.size.toLong() == LENGTH_BYTES + length)
        require(counter == expectedCounter) { "media replay or counter gap" }
        val nonce =
            ByteBuffer.allocate(GCM_NONCE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(ZERO_NONCE_PREFIX)
                .putLong(counter)
                .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(record, 0, AUTHENTICATED_HEADER_BYTES)
        return cipher.doFinal(record, CIPHERTEXT_OFFSET, record.size - CIPHERTEXT_OFFSET)
    }

    private fun hmac(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        parts.forEach(mac::update)
        return mac.doFinal()
    }
}
