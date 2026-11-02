package com.twinpad.android.app.wire

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas criptográficas compartidas del wire — HF y AEAD en un único
 * lugar (DRY; paridad con `core::crypto`). Las tres facetas que delegan
 * aquí son [MediaCrypto] (media TCP), [HandshakeCrypto] (sessión de
 * Pairing, SPEC-001 R4) y el codec UDP de [com.twinpad.android.app.stream]
 * (CR-062 §4.4). Cálculo puro: lanza `GeneralSecurityException` solo ante
 * entornos sin el provider estándar de la JVM/ART (ENG §5 P10).
 */
object CryptoPrimitives {
    /** Algoritmo único del wire de Twinpad: HMAC-SHA256 (RFC 2104). */
    private const val HMAC_SHA256 = "HmacSHA256"

    /** Algoritmo de AEAD único del wire: AES-256-GCM (RFC 8439). */
    private const val GCM_ALGORITHM = "AES/GCM/NoPadding"

    /** Inicializa un [Mac] HMAC-SHA256 (para transcripts de varios updates). */
    fun macSha256(key: ByteArray): Mac {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac
    }

    /** HMAC-SHA256 concated de una vez (extract de HKDF/RFC 5869). */
    fun hmacSha256(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray {
        val mac = macSha256(key)
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** Cipher AES-256-GCM listo en el modo pedido; el AAD lo añade el
     * caller según el wire format de cada canal (header de record TCP §4.2
     * vs header de datagrama UDP §4.4), porque su cobertura de
     * autenticación es distinta por diseño. */
    fun gcmCipher(
        mode: Int,
        key: ByteArray,
        gcmTagBits: Int,
        nonce: ByteArray,
    ): Cipher =
        Cipher.getInstance(GCM_ALGORITHM).apply {
            init(mode, SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(gcmTagBits, nonce))
        }
}
