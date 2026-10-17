package com.twinpad.android.app.wire

import com.twinpad.android.app.core.PairingError
import com.twinpad.android.app.core.PairingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    /**
     * server → app: un Framebuffer completo. `data` ya está decodificado a
     * bytes (UN solo base64 por frame; el lector ya no lo decodifica dos
     * veces — eso estrangulaba el pipeline y el server retrocede por
     * backpressure). `dataBytes` vacío = base64 inválido (descartar).
     */
    data class Frame(
        val frameNumber: Long,
        val width: Int,
        val height: Int,
        val fmt: String,
        val dataBytes: ByteArray,
    ) : StreamMessage() {
        // ByteArray en data class: identidad por contenido (ENG P10).
        override fun equals(other: Any?): Boolean =
            other is Frame &&
                other.frameNumber == frameNumber &&
                other.width == width &&
                other.height == height &&
                other.fmt == fmt &&
                other.dataBytes.contentEquals(dataBytes)

        override fun hashCode(): Int =
            31 * (31 * (31 * (31 * frameNumber.hashCode() + width) + height) + fmt.hashCode()) +
                dataBytes.contentHashCode()
    }

    /** server → app: lote de tiles sobre la última imagen aplicada (CR-002). */
    data class Patch(
        val frameNumber: Long,
        val baseFrameNumber: Long,
        val width: Int,
        val height: Int,
        val tileSize: Int,
        val tiles: List<Tile>,
    ) : StreamMessage()

    data class Tile(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val fmt: String,
        val dataBytes: ByteArray,
    )

    /** server → app: fin del stream. */
    data class Stop(val reason: String) : StreamMessage()
}

/**
 * Decodificador del stream — paridad con `core::stream` (decode estricto;
 * violación → E4 vía [PairingException] con [PairingError.InvalidResponse]).
 */
object StreamCodec {
    private val json = Json

    /** Formatos válidos (SPEC-003 §4; `jpeg_tiles` es modo CR-002). */
    private val FMTS = setOf("gray8", "rgb8", "jpeg", "jpeg_tiles")
    private val IMAGE_FMTS = setOf("gray8", "rgb8", "jpeg")
    private const val TILE_SIZE = 64

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

    /** Lee solo el número de frame sin decodificar base64; permite que el
     * pipeline descarte frames antiguos antes del trabajo pesado. */
    fun frameNumber(line: String): Long? {
        val marker = "\"fn\":"
        val start = line.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = line.indexOfAny(charArrayOf(',', '}'), valueStart)
        if (valueEnd < 0) return null
        return line.substring(valueStart, valueEnd).trim().toLongOrNull()
    }

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
            "video_patch" -> decodePatch(obj)
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
        val validFps = fps in ProtocolConstants.MIN_FPS..ProtocolConstants.MAX_FPS
        if (fmt !in FMTS || !validDimensions(w, h) || !validFps) {
            throw PairingException(PairingError.InvalidResponse)
        }
        return StreamMessage.Start(w, h, fmt, fps)
    }

    private fun decodeFrame(obj: kotlinx.serialization.json.JsonObject): StreamMessage.Frame {
        val w = obj.intField("w")
        val h = obj.intField("h")
        val fmt = obj.stringField("fmt")
        if (fmt !in IMAGE_FMTS) throw PairingException(PairingError.InvalidResponse)
        if (!validDimensions(w, h)) throw PairingException(PairingError.InvalidResponse)
        val frameNumber =
            try {
                obj.getValue("fn").jsonPrimitive.content.toLong()
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
        return StreamMessage.Frame(frameNumber, w, h, fmt, decoded)
    }

    private fun decodePatch(obj: kotlinx.serialization.json.JsonObject): StreamMessage.Patch {
        val frameNumber = obj.longField("fn")
        val baseFrameNumber = obj.longField("base_fn")
        val width = obj.intField("w")
        val height = obj.intField("h")
        val tileSize = obj.intField("tile_size")
        if (!validPatchHeader(frameNumber, baseFrameNumber, width, height, tileSize)) {
            throw PairingException(PairingError.InvalidResponse)
        }
        val columns = (width + TILE_SIZE - 1) / TILE_SIZE
        val occupied = HashSet<Int>()
        val tiles =
            obj.getValue("patches").jsonArray.map { value ->
                decodeTile(value.jsonObject, width, height, columns, occupied)
            }
        if (tiles.isEmpty()) throw PairingException(PairingError.InvalidResponse)
        return StreamMessage.Patch(frameNumber, baseFrameNumber, width, height, tileSize, tiles)
    }

    private fun validPatchHeader(
        frameNumber: Long,
        baseFrameNumber: Long,
        width: Int,
        height: Int,
        tileSize: Int,
    ): Boolean =
        frameNumber > baseFrameNumber && baseFrameNumber >= 0 && tileSize == TILE_SIZE &&
            validDimensions(width, height)

    private fun decodeTile(
        tile: kotlinx.serialization.json.JsonObject,
        width: Int,
        height: Int,
        columns: Int,
        occupied: MutableSet<Int>,
    ): StreamMessage.Tile {
        val x = tile.intField("x")
        val y = tile.intField("y")
        val tileWidth = tile.intField("w")
        val tileHeight = tile.intField("h")
        val fmt = tile.stringField("fmt")
        val geometry = TileGeometry(x, y, tileWidth, tileHeight, width, height)
        if (!validTileGeometry(geometry, fmt)) {
            throw PairingException(PairingError.InvalidResponse)
        }
        val tileIndex = (y / TILE_SIZE) * columns + x / TILE_SIZE
        if (!occupied.add(tileIndex)) throw PairingException(PairingError.InvalidResponse)
        val bytes = HandshakeCrypto.decodeB64(tile.stringField("data"))
        if (bytes.isEmpty()) throw PairingException(PairingError.InvalidResponse)
        return StreamMessage.Tile(x, y, tileWidth, tileHeight, fmt, bytes)
    }

    private data class TileGeometry(
        val x: Int,
        val y: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val width: Int,
        val height: Int,
    )

    private fun validTileGeometry(
        tile: TileGeometry,
        fmt: String,
    ): Boolean {
        val aligned = tile.x % TILE_SIZE == 0 && tile.y % TILE_SIZE == 0
        val inBounds = tile.x >= 0 && tile.y >= 0 && tile.x < tile.width && tile.y < tile.height
        val dimensionsMatch =
            tile.tileWidth == minOf(TILE_SIZE, tile.width - tile.x) &&
                tile.tileHeight == minOf(TILE_SIZE, tile.height - tile.y)
        return fmt == "jpeg" && aligned && inBounds && dimensionsMatch
    }

    private fun kotlinx.serialization.json.JsonObject.longField(name: String): Long =
        try {
            getValue(name).jsonPrimitive.content.toLong()
        } catch (e: IllegalArgumentException) {
            throw PairingException(PairingError.InvalidResponse, e)
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
