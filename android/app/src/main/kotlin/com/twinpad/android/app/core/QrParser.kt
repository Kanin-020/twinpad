package com.twinpad.android.app.core

import java.util.Base64

/**
 * Payload del QR de vinculación — gramática estricta de SPEC-001 §4.1.
 * Cualquier desviación produce [PairingError.QrInvalid] (E5).
 */
data class QrPayload(
    val serverAddr: String,
    val serverPort: Int,
    val token: String,
)

object QrParser {
    /** Esquema URI fijo de la versión 1 (SPEC-001 R1). */
    const val SCHEME: String = "twinpad://v1#"

    /** Rango de puertos válidos (SPEC-001 §4.1). */
    const val MIN_PORT: Int = 1024
    const val MAX_PORT: Int = 65535

    /** Largo máximo de server_addr (SPEC-001 §4.1). */
    private const val MAX_ADDR_LEN: Int = 253

    /** Longitud en bytes del token (SPEC-001 §4.1). */
    private const val TOKEN_BYTES: Int = 32

    private const val KEY_ADDR: String = "s"
    private const val KEY_PORT: String = "p"
    private const val KEY_TOKEN: String = "t"

    /** Valida el contenido crudo del QR y devuelve el payload (E5 si falla). */
    fun parse(raw: String): QrPayload {
        val rest = raw.removePrefixOrNull(SCHEME) ?: throw PairingException(PairingError.QrInvalid)
        if (rest.isEmpty()) throw PairingException(PairingError.QrInvalid)
        val pairs = parsePairs(rest)
        val addr = pairs.addr ?: throw PairingException(PairingError.QrInvalid)
        val port = pairs.port ?: throw PairingException(PairingError.QrInvalid)
        val token = pairs.token ?: throw PairingException(PairingError.QrInvalid)
        validateAddr(addr)
        validatePort(port)
        validateToken(token)
        return QrPayload(serverAddr = addr, serverPort = port, token = token)
    }

    /** Valida el token: base64url sin relleno, decodifica exactamente 32 bytes. */
    fun validateToken(token: String) {
        val bytes = decodeBase64Url(token) { PairingException(PairingError.QrInvalid, it) }
        if (bytes.size != TOKEN_BYTES) throw PairingException(PairingError.QrInvalid)
    }

    private class Pairs {
        var addr: String? = null
        var port: Int? = null
        var token: String? = null
    }

    /** Extrae s/p/t; clave duplicada, extra o valor vacío → E5 (§4.1). */
    private fun parsePairs(rest: String): Pairs {
        val pairs = Pairs()
        val seen = mutableSetOf<String>()
        for (part in rest.split('&')) {
            val separator = part.indexOf('=')
            if (separator <= 0) throw PairingException(PairingError.QrInvalid)
            val key = part.take(separator)
            val value = part.substring(separator + 1)
            if (value.isEmpty() || value.contains('#') || !seen.add(key)) {
                // Vacía, con fragmento o duplicada (aunque el valor previo
                // fuera inválido) → E5.
                throw PairingException(PairingError.QrInvalid)
            }
            when (key) {
                KEY_ADDR -> pairs.addr = value
                KEY_PORT -> pairs.port = value.toIntOrNull()
                KEY_TOKEN -> pairs.token = value
                else -> throw PairingException(PairingError.QrInvalid) // clave extra
            }
        }
        return pairs
    }

    private fun validateAddr(addr: String) {
        val invalid =
            addr.isEmpty() ||
                addr.length > MAX_ADDR_LEN ||
                addr.any { it.isWhitespace() || it == '#' }
        if (invalid) throw PairingException(PairingError.QrInvalid)
    }

    private fun validatePort(port: Int) {
        if (port !in MIN_PORT..MAX_PORT) throw PairingException(PairingError.QrInvalid)
    }

    private fun decodeBase64Url(
        value: String,
        onError: (IllegalArgumentException) -> PairingException,
    ): ByteArray =
        try {
            Base64.getUrlDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw onError(e)
        }

    private fun String.removePrefixOrNull(prefix: String): String? {
        if (startsWith(prefix)) return substring(prefix.length)
        return null
    }
}
