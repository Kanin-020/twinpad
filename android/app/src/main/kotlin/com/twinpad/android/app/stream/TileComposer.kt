package com.twinpad.android.app.stream

import android.graphics.Bitmap
import com.twinpad.android.app.wire.StreamMessage

/** Composición transaccional de patches JPEG sobre el último Framebuffer válido (CR-002). */
internal class TileComposer {
    private var base: Bitmap? = null
    private var lastFrameNumber: Long? = null
    private var awaitingKeyframe = true
    private var streamWidth = 0
    private var streamHeight = 0

    fun reset() {
        base?.recycle()
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
        base?.recycle()
        base = null
        lastFrameNumber = previousFrameNumber
        streamWidth = width
        streamHeight = height
        awaitingKeyframe = true
    }

    fun applyKeyframe(frame: StreamMessage.Frame): Bitmap? {
        if (!matchesStream(frame) || !isNewFrame(frame.frameNumber)) return null
        val decoded = FrameRenderer.decode(frame.dataBytes, frame.width, frame.height, frame.fmt)
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
        val old = base
        base = bitmap
        old?.recycle()
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
        val pixelTiles = if (matchesBase(current, patch)) decodeTiles(patch) else null
        val basePixels = IntArray(current.width * current.height)
        current.getPixels(basePixels, 0, current.width, 0, 0, current.width, current.height)
        val composedPixels = pixelTiles?.let { composePixels(basePixels, current, patch, it) }
        return composedPixels?.let { createBitmap(current.width, current.height, it) }
    }

    private fun composePixels(
        basePixels: IntArray,
        current: Bitmap,
        patch: StreamMessage.Patch,
        pixelTiles: List<PixelTile>,
    ): IntArray? = TileCompositor.compose(basePixels, current.width, current.height, patch.tileSize, pixelTiles)

    private fun createBitmap(
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun matchesBase(
        current: Bitmap,
        patch: StreamMessage.Patch,
    ): Boolean =
        !awaitingKeyframe && patch.baseFrameNumber == lastFrameNumber &&
            patch.frameNumber > (lastFrameNumber ?: -1) &&
            patch.width == current.width && patch.height == current.height && patch.tiles.isNotEmpty()

    private fun decodeTiles(patch: StreamMessage.Patch): List<PixelTile>? {
        val tiles = ArrayList<PixelTile>(patch.tiles.size)
        val occupied = HashSet<Pair<Int, Int>>()
        for (tile in patch.tiles) {
            val pixelTile = decodeTile(tile, patch, occupied) ?: return null
            tiles += pixelTile
        }
        return tiles
    }

    private fun decodeTile(
        tile: StreamMessage.Tile,
        patch: StreamMessage.Patch,
        occupied: MutableSet<Pair<Int, Int>>,
    ): PixelTile? {
        if (!validTile(tile, patch, occupied)) return null
        val bitmap = FrameRenderer.decode(tile.dataBytes, tile.width, tile.height, tile.fmt) ?: return null
        val pixels = IntArray(tile.width * tile.height)
        bitmap.getPixels(pixels, 0, tile.width, 0, 0, tile.width, tile.height)
        bitmap.recycle()
        return PixelTile(tile.x, tile.y, tile.width, tile.height, pixels)
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
