package com.twinpad.android.app.stream

/** Tile decodificado a ARGB antes de tocar el Framebuffer base. */
internal data class PixelTile(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/** Compositor puro: valida el lote entero y devuelve una imagen nueva o null sin alterar la base. */
internal object TileCompositor {
    fun compose(
        base: IntArray,
        width: Int,
        height: Int,
        tileSize: Int,
        tiles: List<PixelTile>,
    ): IntArray? {
        if (!validImage(base, width, height, tileSize, tiles)) return null
        val result = base.copyOf()
        for (tile in tiles) {
            composeTile(result, width, tile)
        }
        return result
    }

    private fun validImage(
        base: IntArray,
        width: Int,
        height: Int,
        tileSize: Int,
        tiles: List<PixelTile>,
    ): Boolean {
        val dimensionsValid = width > 0 && height > 0 && base.size == width * height
        if (!dimensionsValid || tileSize <= 0 || tiles.isEmpty()) return false
        val seen = HashSet<Pair<Int, Int>>()
        return tiles.all { tile -> isValidTile(tile, width, height, tileSize) && seen.add(tile.x to tile.y) }
    }

    private fun isValidTile(
        tile: PixelTile,
        width: Int,
        height: Int,
        tileSize: Int,
    ): Boolean {
        val aligned = tile.x % tileSize == 0 && tile.y % tileSize == 0
        val inBounds = tile.x >= 0 && tile.y >= 0 && tile.x < width && tile.y < height
        val dimensionsMatch =
            tile.width == minOf(tileSize, width - tile.x) &&
                tile.height == minOf(tileSize, height - tile.y)
        val pixelCountMatches = tile.pixels.size == tile.width * tile.height
        return aligned && inBounds && dimensionsMatch && pixelCountMatches
    }

    private fun composeTile(
        result: IntArray,
        imageWidth: Int,
        tile: PixelTile,
    ) {
        for (row in 0 until tile.height) {
            val src = row * tile.width
            val dst = (tile.y + row) * imageWidth + tile.x
            tile.pixels.copyInto(result, dst, src, src + tile.width)
        }
    }
}
