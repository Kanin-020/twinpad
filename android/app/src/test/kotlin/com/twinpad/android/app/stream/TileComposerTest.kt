package com.twinpad.android.app.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-028 — verifies SPEC-003/CR-002: atomic tile composition and invalid-batch rejection. */
class TileComposerTest {
    @Test
    fun valid_tile_changes_only_its_region_and_keeps_base_immutable() {
        val base = IntArray(8 * 8) { 1 }
        val result =
            TileCompositor.compose(
                base = base,
                width = 8,
                height = 8,
                tileSize = 4,
                tiles = listOf(PixelTile(4, 4, 4, 4, IntArray(16) { 2 })),
            )
        assertArrayEquals(IntArray(64) { index -> if (index / 8 >= 4 && index % 8 >= 4) 2 else 1 }, result)
        assertArrayEquals(IntArray(64) { 1 }, base)
    }

    @Test
    fun invalid_or_duplicate_tile_rejects_whole_batch_without_changing_base() {
        val base = IntArray(64) { 1 }
        val valid = PixelTile(0, 0, 4, 4, IntArray(16) { 2 })
        assertNull(TileCompositor.compose(base, 8, 8, 4, listOf(valid, valid)))
        assertNull(TileCompositor.compose(base, 8, 8, 4, listOf(PixelTile(4, 4, 3, 4, IntArray(12)))))
        assertArrayEquals(IntArray(64) { 1 }, base)
    }
}
