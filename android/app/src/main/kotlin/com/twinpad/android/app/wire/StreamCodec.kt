package com.twinpad.android.app.wire

import com.twinpad.android.app.core.PairingError
import com.twinpad.android.app.core.PairingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mensajes del Stream de video de la Segunda pantalla — SPEC-003 §4/§12.
 * Viajan por la conexión NDJSON de la sesión una vez establecida.
 */
sealed class StreamMessage {
    /** server → app: anuncia el stream y sus parámetros vigentes. */
    data class Start(
        val width: Int,
        val height: Int,
        val fmt: String,
        val fps: Int,
    ) : StreamMessage()

    /** server → app: un Framebuffer completo (base64url, sin compresión v1). */
    data class Frame(
        val frameNumber: Long,
        val width: Int,
        val height: Int,
        val fmt: String,
        val data: String,
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

    /** Formatos válidos (SPEC-003 §4; `jpeg` entra con CR-001). */
    private val FMTS = setOf("gray8", "rgb8", "jpeg")

    private val STOP_REASONS = setOf("plugin_stopped", "server_shutdown")

    /** Bytes por píxel por formato (SPEC-003 §12); `null` para los
     * comprimidos (`jpeg`, CR-001: tamaño libre). */
    private fun bpp(fmt: String): Int? =
        when (fmt) {
            "gray8" -> ProtocolConstants.GRAY_BYTES_PER_PIXEL
            "rgb8" -> ProtocolConstants.RGB_BYTES_PER_PIXEL
            "jpeg" -> null
            else -> throw PairingException(PairingError.InvalidResponse)
        }

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
            "video_frame" -> decodeFrame(obj)
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
        if (fmt !in FMTS) throw PairingException(PairingError.InvalidResponse)
        if (!validDimensions(w, h) || fps !in ProtocolConstants.MIN_FPS..ProtocolConstants.MAX_FPS) {
            throw PairingException(PairingError.InvalidResponse)
        }
        return StreamMessage.Start(w, h, fmt, fps)
    }

    private fun decodeFrame(obj: kotlinx.serialization.json.JsonObject): StreamMessage.Frame {
        val w = obj.intField("w")
        val h = obj.intField("h")
        val fmt = obj.stringField("fmt")
        if (fmt !in FMTS) throw PairingException(PairingError.InvalidResponse)
        if (!validDimensions(w, h)) throw PairingException(PairingError.InvalidResponse)
        val frameNumber =
            try {
                obj.getValue("fn_").jsonPrimitive.content.toLong()
            } catch (e: IllegalArgumentException) {
                throw PairingException(PairingError.InvalidResponse, e)
            }
        val data = obj.stringField("data")
        // §5: el frame inconsistente se descarta en la frontera (E4).
        // CR-001: `jpeg` tiene tamaño libre; los crudos miden w×h×bpp.
        val decoded = HandshakeCrypto.decodeB64(data)
        val expectedSize = bpp(fmt)?.let { bppValue -> w * h * bppValue }
        if (expectedSize != null && decoded.size != expectedSize) {
            throw PairingException(PairingError.InvalidResponse)
        }
        if (decoded.isEmpty()) throw PairingException(PairingError.InvalidResponse)
        return StreamMessage.Frame(frameNumber, w, h, fmt, data)
    }

    private fun kotlinx.serialization.json.JsonObject.stringField(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() }
            ?.content
            ?: throw PairingException(PairingError.InvalidResponse)

    private fun kotlinx.serialization.json.JsonObject.intField(name: String): Int =
        try {
            getValue(name).jsonPrimitive.content.toInt()
        } catch (e: IllegalArgumentException) {
            throw PairingException(PairingError.InvalidResponse, e)
        }
}
