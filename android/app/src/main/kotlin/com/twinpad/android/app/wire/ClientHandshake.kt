package com.twinpad.android.app.wire

import com.twinpad.android.app.core.PairingError
import com.twinpad.android.app.core.PairingException
import com.twinpad.android.app.core.QrParser

/**
 * Handshake del lado app — misma secuencia que `core::handshake::ClientHandshake`
 * (SPEC-001 §7, DES-002 §4): `start` → `onPairChallenge` → `onPairAck`.
 * La clave de sesión vive solo en memoria (SPEC-002 R6).
 */
class ClientHandshake private constructor(
    private val tokenStr: String,
    private val requestLine: String,
) {
    private var sessionKey: ByteArray? = null

    companion object {
        /** SPEC-001 §7 pasos 2–5: valida el token del QR y produce `pair_request`. */
        fun start(tokenStr: String): Pair<ClientHandshake, HandshakeMessage.Request> {
            QrParser.validateToken(tokenStr) // E5 si el token no es conforme
            val request = HandshakeMessage.Request(schema = SCHEMA, token = tokenStr)
            return Pair(ClientHandshake(tokenStr, HandshakeCodec.encode(request)), request)
        }
    }

    /** SPEC-001 §7 paso 8: challenge → proof, con transcript idéntico al core. */
    fun onPairChallenge(
        nonceS: ByteArray,
        nonceC: ByteArray,
    ): HandshakeMessage.Proof {
        if (nonceS.size != ProtocolConstants.NONCE_BYTES || nonceC.size != ProtocolConstants.NONCE_BYTES) {
            throw PairingException(PairingError.InvalidResponse)
        }
        val key =
            HandshakeCrypto.deriveSessionKey(
                HandshakeCrypto.decodeB64(tokenStr),
                nonceS,
            )
        sessionKey = key
        val challengeLine =
            HandshakeCodec.encode(
                HandshakeMessage.Challenge(nonceS = HandshakeCrypto.encodeB64(nonceS)),
            )
        val proof = HandshakeCrypto.computeProof(key, requestLine, challengeLine, nonceS, nonceC)
        return HandshakeMessage.Proof(
            nonceC = HandshakeCrypto.encodeB64(nonceC),
            proof = HandshakeCrypto.encodeB64(proof),
        )
    }

    /** SPEC-001 §7 paso 10: el `pair_ack` completa la sesión (U4).
     * Un `pair_ack` sin challenge previo es un mensaje fuera de secuencia
     * → E3 (SPEC-001 §9); nunca una excepción cruda (ENG §6). La validación
     * de longitud del session_id (16 B) ya la hizo el codec (E4). */
    @Suppress("UNUSED_PARAMETER")
    fun onPairAck(sessionId: String) {
        if (sessionKey == null) {
            throw PairingException(PairingError.Rejected)
        }
    }

    /** Clave de sesión (solo memoria; SPEC-002 R6). */
    fun sessionKeyOrNull(): ByteArray? = sessionKey
}
