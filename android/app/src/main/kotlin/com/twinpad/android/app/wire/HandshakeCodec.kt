package com.twinpad.android.app.wire

import com.twinpad.android.app.core.PairingError
import com.twinpad.android.app.core.PairingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * Versión del protocolo (SPEC-001 R1).
 */
const val SCHEMA: String = "twinpad.v1"

/** Longitud en bytes de nonces y proofs (SPEC-001 §4.2). */
private const val NONCE_BYTES: Int = ProtocolConstants.NONCE_BYTES

/** Longitud en bytes del session_id (SPEC-001 §4.2). */
private const val SESSION_ID_BYTES: Int = ProtocolConstants.SESSION_ID_BYTES

/** Largo máximo del campo message de pair_error (SPEC-001 §4.2). */
private const val MAX_ERROR_MESSAGE_LEN: Int = 200

/**
 * Mensajes del Handshake — contrato exacto de SPEC-001 §4.2.
 */
sealed class HandshakeMessage {
    /** app → servidor: apertura del Handshake con el token del QR. */
    data class Request(val schema: String, val token: String) : HandshakeMessage()

    /** servidor → app: nonce del servidor. */
    data class Challenge(val nonceS: String) : HandshakeMessage()

    /** app → servidor: prueba de conocimiento del token. */
    data class Proof(val nonceC: String, val proof: String) : HandshakeMessage()

    /** servidor → app: Handshake exitoso. */
    data class Ack(val sessionId: String) : HandshakeMessage()

    /** servidor → app: rechazo con código E1–E5. */
    data class Error(val code: String, val message: String) : HandshakeMessage()
}

/**
 * Codificador/decodificador NDJSON de [HandshakeMessage].
 * Decode estricto: `type` desconocido, campo faltante/extra o longitud
 * inválida → E4 (SPEC-001 §9); encode nunca falla para mensajes válidos.
 */
object HandshakeCodec {
    private val json = Json

    fun encode(msg: HandshakeMessage): String =
        when (msg) {
            is HandshakeMessage.Request ->
                """{"type":"pair_request","schema":"${msg.schema}","token":"${msg.token}"}"""
            is HandshakeMessage.Challenge ->
                """{"type":"pair_challenge","nonce_s":"${msg.nonceS}"}"""
            is HandshakeMessage.Proof ->
                """{"type":"pair_proof","nonce_c":"${msg.nonceC}","proof":"${msg.proof}"}"""
            is HandshakeMessage.Ack ->
                """{"type":"pair_ack","session_id":"${msg.sessionId}"}"""
            is HandshakeMessage.Error ->
                // JsonPrimitive.toString() emite el literal JSON escapado con comillas.
                """{"type":"pair_error","code":"${msg.code}","message":${JsonPrimitive(msg.message)}}"""
        }

    fun decode(line: String): HandshakeMessage {
        val obj: JsonObject =
            try {
                json.parseToJsonElement(line).jsonObject
            } catch (e: IllegalArgumentException) {
                throw PairingException(PairingError.InvalidResponse, e)
            }
        return when (val type = obj.stringField("type")) {
            "pair_request" -> decodeRequest(obj)
            "pair_challenge" -> decodeChallenge(obj)
            "pair_proof" -> decodeProof(obj)
            "pair_ack" -> decodeAck(obj)
            "pair_error" -> decodeError(obj)
            else -> throw unknownType(type)
        }
    }

    private fun decodeRequest(obj: JsonObject): HandshakeMessage.Request {
        val schema = obj.stringField("schema")
        val token = obj.stringField("token").also { expectLen(it, NONCE_BYTES) }
        if (schema != SCHEMA) throw PairingException(PairingError.InvalidResponse)
        return HandshakeMessage.Request(schema, token)
    }

    private fun decodeChallenge(obj: JsonObject): HandshakeMessage.Challenge =
        HandshakeMessage.Challenge(obj.stringField("nonce_s").also { expectLen(it, NONCE_BYTES) })

    private fun decodeProof(obj: JsonObject): HandshakeMessage.Proof {
        val nonceC = obj.stringField("nonce_c").also { expectLen(it, NONCE_BYTES) }
        val proof = obj.stringField("proof").also { expectLen(it, NONCE_BYTES) }
        return HandshakeMessage.Proof(nonceC, proof)
    }

    private fun decodeAck(obj: JsonObject): HandshakeMessage.Ack =
        HandshakeMessage.Ack(obj.stringField("session_id").also { expectLen(it, SESSION_ID_BYTES) })

    private fun decodeError(obj: JsonObject): HandshakeMessage.Error {
        val code = obj.stringField("code")
        if (code !in setOf("E1", "E2", "E3", "E4", "E5")) {
            throw PairingException(PairingError.InvalidResponse)
        }
        val message = obj.stringField("message")
        if (message.isEmpty() || message.length > MAX_ERROR_MESSAGE_LEN) {
            throw PairingException(PairingError.InvalidResponse)
        }
        return HandshakeMessage.Error(code, message)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unknownType(type: String): PairingException = PairingException(PairingError.InvalidResponse)

    private fun JsonObject.stringField(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString || it.content.isNotEmpty() }
            ?.content
            ?: throw PairingException(PairingError.InvalidResponse)

    private fun expectLen(
        value: String,
        bytes: Int,
    ) {
        val decoded =
            try {
                Base64.getUrlDecoder().decode(value)
            } catch (e: IllegalArgumentException) {
                throw PairingException(PairingError.InvalidResponse, e)
            }
        if (decoded.size != bytes) throw PairingException(PairingError.InvalidResponse)
    }
}
