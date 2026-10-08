package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mensajes del stream de audio — SPEC-005 §4 (lado app; paridad con
 * `core::audio`). `s16le` PCM sin compresión (OQ §17 del SPEC).
 */
sealed class AudioMessage {
    /** Anuncia el stream y configura la salida (§7 paso 1). */
    data class Start(
        val rate: Int,
        val channels: Int,
        val sampleFmt: String,
    ) : AudioMessage()

    /** Un fragmento de PCM (§7 paso 2); ordena por `seq` (R2). */
    data class Chunk(
        val seq: Long,
        val ts: Long,
        val data: String,
    ) : AudioMessage()

    /** Fin del stream (§7 paso 4). */
    data class Stop(
        val reason: String,
    ) : AudioMessage()
}

/**
 * Decodificador de mensajes de audio — paridad con `core::audio` (E4 ante
 * cualquier desviación de §4). La validación de rangos la hace la máquina
 * (`AudioStateMachine`) y el codec compartido en el server.
 */
object AudioCodec {
    private val json = Json

    /** Decodifica la línea; lanza si no es un mensaje de audio válido. */
    fun decode(line: String): AudioMessage {
        val obj =
            json.parseToJsonElement(line).let { it as? JsonObject }
                ?: throw com.twinpad.android.app.core.PairingException(
                    com.twinpad.android.app.core.PairingError.InvalidResponse,
                )
        return when (obj["type"]?.jsonPrimitive?.content) {
            "audio_start" ->
                AudioMessage.Start(
                    rate = obj["rate"]!!.jsonPrimitive.content.toInt(),
                    channels = obj["channels"]!!.jsonPrimitive.content.toInt(),
                    sampleFmt = obj["sample_fmt"]!!.jsonPrimitive.content,
                )
            "audio_chunk" ->
                AudioMessage.Chunk(
                    seq = obj["seq"]!!.jsonPrimitive.content.toLong(),
                    ts = obj["ts"]!!.jsonPrimitive.content.toLong(),
                    data = obj["data"]!!.jsonPrimitive.content,
                )
            "audio_stop" ->
                AudioMessage.Stop(
                    reason = obj["reason"]!!.jsonPrimitive.content,
                )
            else ->
                throw com.twinpad.android.app.core.PairingException(
                    com.twinpad.android.app.core.PairingError.InvalidResponse,
                )
        }
    }
}
