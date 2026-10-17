package com.twinpad.android.app.stream

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.twinpad.android.app.wire.StreamMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** TEST-029 — verifies SPEC-003: a published Framebuffer stays readable after replacement/reset. */
@RunWith(AndroidJUnit4::class)
class TileComposerBitmapTest {
    @Test
    fun published_framebuffers_remain_readable_after_replacement_and_reset() {
        val composer = TileComposer()
        composer.beginStream(16, 16)
        val first = composer.applyKeyframe(keyframe(0, Color.RED))
        assertNotNull(first)
        val second = composer.applyKeyframe(keyframe(1, Color.GREEN))
        assertNotNull(second)

        assertReadable(first!!, Color.RED)
        composer.reset()
        assertReadable(second!!, Color.GREEN)
    }

    @Test
    fun patch_composition_updates_only_published_frame_and_preserves_base_framebuffer() {
        val composer = TileComposer()
        composer.beginStream(16, 16)
        val base = composer.applyKeyframe(keyframe(0, Color.RED))
        assertNotNull(base)

        val patched =
            composer.applyPatch(
                StreamMessage.Patch(
                    frameNumber = 1,
                    baseFrameNumber = 0,
                    width = 16,
                    height = 16,
                    tileSize = 64,
                    tiles = listOf(tile(0, 0, 16, 16, Color.GREEN)),
                ),
            )
        assertNotNull(patched)
        assertReadable(base!!, Color.RED)
        assertReadable(patched!!, Color.GREEN)
    }

    private fun tile(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
    ): StreamMessage.Tile {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.eraseColor(color)
        val output = ByteArrayOutputStream()
        check(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
        source.recycle()
        return StreamMessage.Tile(
            x,
            y,
            width,
            height,
            "jpeg",
            output.toByteArray(),
        )
    }

    private fun keyframe(
        frameNumber: Long,
        color: Int,
    ): StreamMessage.Frame {
        val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        source.eraseColor(color)
        val output = ByteArrayOutputStream()
        check(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
        source.recycle()
        return StreamMessage.Frame(
            frameNumber,
            16,
            16,
            "jpeg",
            output.toByteArray(),
        )
    }

    private fun assertReadable(
        bitmap: Bitmap,
        expected: Int,
    ) {
        assertFalse("published bitmap was recycled", bitmap.isRecycled)
        val pixel = bitmap.getPixel(8, 8)
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(pixel)) <= 8)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(pixel)) <= 8)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(pixel)) <= 8)
    }
}
