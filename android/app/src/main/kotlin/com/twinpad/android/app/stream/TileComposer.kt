package com.twinpad.android.app.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import com.twinpad.android.app.wire.StreamMessage

/** Composición transaccional de patches JPEG sobre el último Framebuffer válido (CR-002). */
internal class TileComposer {
    private var base: Bitmap? = null
    private var lastFrameNumber: Long? = null
    private var awaitingKeyframe = true
    private var streamWidth = 0
    private var streamHeight = 0

    fun reset() {
        // El bitmap previo puede seguir referenciado por StateFlow/ImageView;
        // soltar nuestra referencia y dejar que el GC lo recoja tras el draw.
        base = null
        lastFrameNumber = null
        awaitingKeyframe = true
        streamWidth = 0
        streamHeight = 0
    }

    fun beginStream(
        width: Int,
        height: Int,
    ) {
        val previousFrameNumber = lastFrameNumber.takeIf { streamWidth > 0 && streamHeight > 0 }
        // No reciclar: el Framebuffer anterior puede seguir mostrándose hasta
        // que llegue el keyframe correspondiente a estas dimensiones.
        base = null
        lastFrameNumber = previousFrameNumber
        streamWidth = width
        streamHeight = height
        awaitingKeyframe = true
    }

    fun applyKeyframe(frame: StreamMessage.Frame): Bitmap? {
        if (!matchesStream(frame) || !isNewFrame(frame.frameNumber)) return null
        val decoded =
            VideoTrace.section("video.jpeg_decode") {
                FrameRenderer.decode(frame.dataBytes, frame.width, frame.height, frame.fmt)
            }
        if (decoded == null) {
            awaitingKeyframe = true
            return null
        }
        streamWidth = frame.width
        streamHeight = frame.height
        replaceBase(decoded)
        lastFrameNumber = frame.frameNumber
        awaitingKeyframe = false
        return decoded
    }

    private fun matchesStream(frame: StreamMessage.Frame): Boolean =
        streamWidth > 0 && streamHeight > 0 && frame.fmt == "jpeg" &&
            frame.width == streamWidth && frame.height == streamHeight

    private fun isNewFrame(frameNumber: Long): Boolean {
        return lastFrameNumber?.let { frameNumber > it } ?: (frameNumber == 0L)
    }

    private fun replaceBase(bitmap: Bitmap) {
        // La UI puede seguir dibujando la base anterior después de publicar
        // el frame nuevo. Bitmap.recycle() invalida también esas referencias;
        // soltarla permite que se libere cuando StateFlow/ImageView terminen.
        base = bitmap
    }

    fun applyPatch(patch: StreamMessage.Patch): Bitmap? {
        val current = base
        val result = current?.let { composePatch(it, patch) }
        if (result == null) return rejectPatch()
        replaceBase(result)
        lastFrameNumber = patch.frameNumber
        awaitingKeyframe = false
        return result
    }

    private fun composePatch(
        current: Bitmap,
        patch: StreamMessage.Patch,
    ): Bitmap? {
        if (!matchesBase(current, patch)) return null
        val decodedTiles = decodeTiles(patch) ?: return null
        return try {
            val composed =
                VideoTrace.section("video.framebuffer_copy") {
                    current.copy(Bitmap.Config.ARGB_8888, true)
                }
            if (composed != null) {
                VideoTrace.section("video.tile_draw") {
                    val canvas = Canvas(composed)
                    decodedTiles.forEach { tile ->
                        canvas.drawBitmap(tile.bitmap, tile.x.toFloat(), tile.y.toFloat(), null)
                    }
                }
            }
            composed
        } finally {
            decodedTiles.forEach { it.bitmap.recycle() }
        }
    }

    private fun matchesBase(
        current: Bitmap,
        patch: StreamMessage.Patch,
    ): Boolean =
        !awaitingKeyframe && patch.baseFrameNumber == lastFrameNumber &&
            patch.frameNumber > (lastFrameNumber ?: -1) &&
            patch.width == current.width && patch.height == current.height && patch.tiles.isNotEmpty()

    private data class DecodedTile(
        val x: Int,
        val y: Int,
        val bitmap: Bitmap,
    )

    private fun decodeTiles(patch: StreamMessage.Patch): List<DecodedTile>? {
        val tiles = ArrayList<DecodedTile>(patch.tiles.size)
        val occupied = HashSet<Pair<Int, Int>>()
        for (tile in patch.tiles) {
            if (!validTile(tile, patch, occupied)) {
                tiles.forEach { it.bitmap.recycle() }
                return null
            }
            val bitmap =
                VideoTrace.section("video.jpeg_tile_decode") {
                    FrameRenderer.decode(tile.dataBytes, tile.width, tile.height, tile.fmt)
                }
            if (bitmap == null) {
                tiles.forEach { it.bitmap.recycle() }
                return null
            }
            tiles += DecodedTile(tile.x, tile.y, bitmap)
        }
        return tiles
    }

    private fun validTile(
        tile: StreamMessage.Tile,
        patch: StreamMessage.Patch,
        occupied: MutableSet<Pair<Int, Int>>,
    ): Boolean {
        val aligned = tile.x % patch.tileSize == 0 && tile.y % patch.tileSize == 0
        val inBounds =
            tile.x >= 0 && tile.y >= 0 &&
                tile.x + tile.width <= patch.width && tile.y + tile.height <= patch.height
        val correctSize =
            tile.width == minOf(patch.tileSize, patch.width - tile.x) &&
                tile.height == minOf(patch.tileSize, patch.height - tile.y)
        return tile.fmt == "jpeg" && aligned && inBounds && correctSize && occupied.add(tile.x to tile.y)
    }

    private fun rejectPatch(): Bitmap? {
        awaitingKeyframe = true
        return null
    }
}
