package com.twinpad.android.app.wire

import com.twinpad.android.app.core.PairingException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografía del Handshake del lado app — HKDF-SHA256 + HMAC-SHA256
 * (SPEC-001 R4; paridad bit a bit con `core::crypto`, DES-001 §4).
 *
 * HKDF se implementa según RFC 5869 (extract + expand de un bloque) sobre
 * `javax.crypto.Mac`; ambos cálculos son puros e infalibles (ENG §5 P10).
 */
object HandshakeCrypto {
    /** Info fija de HKDF (DES-001 §4). */
    private val HKDF_INFO: ByteArray = "twinpad.v1 session".toByteArray(Charsets.UTF_8)

    private const val HMAC_ALGORITHM: String = "HmacSHA256"
    private const val KEY_BYTES: Int = 32

    /** Deriva la clave de sesión: HKDF-SHA256(ikm = token, salt = nonce_s) —
     * SPEC-001 §7 paso 8. */
    fun deriveSessionKey(
        token: ByteArray,
        nonceS: ByteArray,
    ): ByteArray {
        require(token.size == KEY_BYTES) { "token debe ser de 32 bytes" }
        require(nonceS.size == KEY_BYTES) { "nonce_s debe ser de 32 bytes" }
        // Extract: PRK = HMAC(salt, ikm)
        val prk = hmac(nonceS, token)
        // Expand (N=1 para 32 bytes con SHA-256): OKM = HMAC(PRK, info ‖ 0x01)
        val mac = macInstance(prk)
        mac.update(HKDF_INFO)
        mac.update(0x01)
        return mac.doFinal()
    }

    /** Computa el proof sobre el transcript fijo (DES-001 §4):
     * encode(pair_request) ‖ encode(pair_challenge) ‖ nonce_s ‖ nonce_c. */
    fun computeProof(
        sessionKey: ByteArray,
        requestLine: String,
        challengeLine: String,
        nonceS: ByteArray,
        nonceC: ByteArray,
    ): ByteArray {
        val mac = macInstance(sessionKey)
        mac.update(requestLine.toByteArray(Charsets.UTF_8))
        mac.update(challengeLine.toByteArray(Charsets.UTF_8))
        mac.update(nonceS)
        mac.update(nonceC)
        return mac.doFinal()
    }

    /** Parámetros del proof agrupados (DES-002: ≤ 3 posicionales, ENG P4). */
    data class ProofContext(
        val sessionKey: ByteArray,
        val requestLine: String,
        val challengeLine: String,
        val nonceS: ByteArray,
        val nonceC: ByteArray,
    )

    /** Decodifica base64url sin relleno; E4 si es inválido (causa preservada). */
    fun decodeB64(value: String): ByteArray =
        try {
            Base64.getUrlDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw PairingException(
                com.twinpad.android.app.core.PairingError.InvalidResponse,
                e,
            )
        }

    /** Codifica base64url canónico sin relleno. */
    fun encodeB64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Nonce criptográficamente aleatorio de 32 bytes (el RNG vive aquí,
     * frontera de I/O según DES-002 §11). */
    fun randomNonce(): ByteArray = ByteArray(ProtocolConstants.NONCE_BYTES).also { secureRandom().nextBytes(it) }

    private fun macInstance(key: ByteArray): Mac {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = macInstance(key)
        mac.update(data)
        return mac.doFinal()
    }

    private fun secureRandom(): java.security.SecureRandom = java.security.SecureRandom()
}
