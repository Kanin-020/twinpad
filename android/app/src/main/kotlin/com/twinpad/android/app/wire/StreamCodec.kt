package com.twinpad.android.app.wire

import com.twinpad.android.app.core.PairingError
import com.twinpad.android.app.core.PairingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mensajes del Stream de video de la Segunda pantalla — SPEC-003 §4/§12.
 * Viajan por la conexión NDJSON de la sesión una vez establecida. Desde
 * CR-014 el video H.264 viaja por el canal media y la v1.5.0 no define
 * `video_frame`, `video_patch` ni `stream_gap`: solo quedan el anuncio y el
 * fin del stream.
 */
sealed class StreamMessage {
    /** server → app: anuncia el stream y sus parámetros vigentes. */
    data class Start(
        val width: Int,
        val height: Int,
        val fmt: String,
        val fps: Int,
        val mediaPort: Int,
    ) : StreamMessage()

    /** server → app: fin del stream. */
    data class Stop(val reason: String) : StreamMessage()
}

/**
 * Decodificador del stream — paridad con `core::stream` (decode estricto;
 * violación → E4 vía [PairingException] con [PairingError.InvalidResponse]).
 */
object StreamCodec {
    private val json = Json

    /** Formato válido (SPEC-003 §4.1: `fmt="h264"`). */
    private val FMTS = setOf("h264")

    private val STOP_REASONS = setOf("plugin_stopped", "server_shutdown")

    /** Dimensiones dentro del rango de SPEC-003 §4. */
    private fun validDimensions(
        w: Int,
        h: Int,
    ): Boolean =
        w >= ProtocolConstants.MIN_DIM && h >= ProtocolConstants.MIN_DIM &&
            w <= ProtocolConstants.MAX_WIDTH && h <= ProtocolConstants.MAX_HEIGHT

    fun decode(line: String): StreamMessage {
        val obj =
            try {
                json.parseToJsonElement(line).jsonObject
            } catch (e: IllegalArgumentException) {
                throw PairingException(PairingError.InvalidResponse, e)
            }
        return decodeType(obj)
    }

    private fun decodeType(obj: kotlinx.serialization.json.JsonObject): StreamMessage =
        when (obj["type"]?.jsonPrimitive?.content) {
            "stream_start" -> decodeStart(obj)
            "stream_stop" ->
                StreamMessage.Stop(
                    obj.stringField("reason").also {
                        if (it !in STOP_REASONS) throw PairingException(PairingError.InvalidResponse)
                    },
                )
            else -> throw PairingException(PairingError.InvalidResponse) // tipo desconocido
        }

    private fun decodeStart(obj: kotlinx.serialization.json.JsonObject): StreamMessage.Start {
        val w = obj.intField("w")
        val h = obj.intField("h")
        val fps = obj.intField("fps")
        val fmt = obj.stringField("fmt")
        val mediaPort = obj.intField("media_port")
        if (fmt !in FMTS) throw PairingException(PairingError.InvalidResponse)
        if (!validDimensions(w, h)) throw PairingException(PairingError.InvalidResponse)
        if (fps !in ProtocolConstants.MIN_FPS..ProtocolConstants.MAX_FPS) {
            throw PairingException(PairingError.InvalidResponse)
        }
        if (mediaPort != MediaCrypto.PORT) throw PairingException(PairingError.InvalidResponse)
        return StreamMessage.Start(w, h, fmt, fps, mediaPort)
    }

    private fun kotlinx.serialization.json.JsonObject.stringField(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() }
            ?.content
            ?: throw PairingException(PairingError.InvalidResponse)

    private fun kotlinx.serialization.json.JsonObject.intField(name: String): Int =
        try {
            getValue(name).jsonPrimitive.content.toInt()
        } catch (e: NoSuchElementException) {
            throw PairingException(PairingError.InvalidResponse, e)
        } catch (e: IllegalArgumentException) {
            throw PairingException(PairingError.InvalidResponse, e)
        }
}
