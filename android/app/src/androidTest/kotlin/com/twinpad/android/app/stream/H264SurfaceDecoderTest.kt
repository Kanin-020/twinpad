package com.twinpad.android.app.stream

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** TEST-074 — verifies SPEC-003: authenticated H.264 AUs enter MediaCodec only at IDR. */
@RunWith(AndroidJUnit4::class)
class H264SurfaceDecoderTest {
    @Test
    fun surface_decoder_waits_for_idr_and_recovers_after_reset_and_dimension_change() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val accessUnits =
            context.assets.open("h264-baseline-three-frames.h264").use { input ->
                splitAccessUnits(input.readBytes())
            }
        assertEquals("fixture should contain one IDR and two P access units", 3, accessUnits.size)
        assertTrue(accessUnits[0].idr)
        assertFalse(accessUnits[1].idr)
        assertFalse(accessUnits[2].idr)

        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(64, 64) }
        val surface = Surface(texture)
        val presented = AtomicInteger(0)
        val firstPresented = CountDownLatch(1)
        val recoveredPresented = CountDownLatch(1)
        val reconnectedPresented = CountDownLatch(1)
        var decoder =
            H264SurfaceDecoder(surface) { frameNumber ->
                when (frameNumber) {
                    in 10..19 -> {
                        presented.incrementAndGet()
                        firstPresented.countDown()
                    }
                    in 30..39 -> {
                        presented.incrementAndGet()
                        recoveredPresented.countDown()
                    }
                }
            }
        try {
            decoder.configure(64, 64)
            decoder.queue(accessUnits[1].asFrame(9, 33_333))
            Thread.sleep(100)
            assertEquals("P-frame before IDR must not be presented", 0, presented.get())

            decoder.queue(accessUnits[0].asFrame(10, 66_666))
            repeat(6) { index ->
                decoder.queue(accessUnits[1].asFrame(11L + index, 100_000L + index * 33_333L))
                Thread.sleep(20)
            }
            assertTrue("IDR did not produce a decoded Surface output", firstPresented.await(3, TimeUnit.SECONDS))

            decoder.resetForDiscontinuity()
            decoder.queue(accessUnits[1].asFrame(20, 333_333))
            Thread.sleep(100)
            val beforeRecoveryIdr = presented.get()
            assertTrue("P-frame after discontinuity bypassed the IDR gate", beforeRecoveryIdr in 1..6)

            // Configure must discard decoder state on dimension changes; configure back to
            // the fixture dimensions before feeding its IDR parameter sets again.
            decoder.configure(128, 128)
            decoder.configure(64, 64)
            decoder.queue(accessUnits[1].asFrame(29, 366_666))
            Thread.sleep(100)
            assertEquals("dimension reset must return to IDR wait", beforeRecoveryIdr, presented.get())
            decoder.queue(accessUnits[0].asFrame(30, 400_000))
            repeat(6) { index ->
                decoder.queue(accessUnits[2].asFrame(31L + index, 433_333L + index * 33_333L))
                Thread.sleep(20)
            }
            assertTrue("decoder did not resume after a fresh IDR", recoveredPresented.await(3, TimeUnit.SECONDS))

            // A media reconnect creates a new MediaCodec while Android keeps the same
            // Surface attached. Releasing the old codec must free its Surface consumer.
            decoder.close()
            decoder =
                H264SurfaceDecoder(surface) { frameNumber ->
                    if (frameNumber in 40..49) {
                        presented.incrementAndGet()
                        reconnectedPresented.countDown()
                    }
                }
            decoder.configure(64, 64)
            decoder.queue(accessUnits[0].asFrame(40, 700_000))
            repeat(6) { index ->
                decoder.queue(accessUnits[1].asFrame(41L + index, 733_333L + index * 33_333L))
                Thread.sleep(20)
            }
            assertTrue(
                "new decoder could not reconnect to the still-attached Surface",
                reconnectedPresented.await(3, TimeUnit.SECONDS),
            )
        } finally {
            decoder.close()
            surface.release()
            texture.release()
        }
    }

    private data class FixtureAccessUnit(val idr: Boolean, val bytes: ByteArray) {
        fun asFrame(
            frameNumber: Long,
            timestampUs: Long,
        ) = H264MediaClient.EncodedAccessUnit(frameNumber, timestampUs, idr, bytes)
    }

    private fun splitAccessUnits(annexB: ByteArray): List<FixtureAccessUnit> {
        val starts = ArrayList<Pair<Int, Int>>()
        var index = 0
        while (index + 3 < annexB.size) {
            val prefixSize =
                when {
                    annexB[index] == 0.toByte() && annexB[index + 1] == 0.toByte() && annexB[index + 2] == 1.toByte() -> 3
                    index + 4 <= annexB.size && annexB[index] == 0.toByte() && annexB[index + 1] == 0.toByte() &&
                        annexB[index + 2] == 0.toByte() && annexB[index + 3] == 1.toByte() -> 4
                    else -> 0
                }
            if (prefixSize == 0) {
                index++
            } else {
                starts += index to prefixSize
                index += prefixSize + 1
            }
        }
        var current = ByteArrayOutputStream()
        var currentHasVcl = false
        var currentIdr = false
        val result = ArrayList<FixtureAccessUnit>()
        for ((position, prefixSize) in starts) {
            val nalType = annexB[position + prefixSize].toInt() and 0x1f
            if (nalType == 9 && currentHasVcl) {
                result += FixtureAccessUnit(currentIdr, current.toByteArray())
                current = ByteArrayOutputStream()
                currentHasVcl = false
                currentIdr = false
            }
            val next = starts.firstOrNull { it.first > position }?.first ?: annexB.size
            current.write(annexB, position, next - position)
            if (nalType == 1 || nalType == 5) {
                currentHasVcl = true
                currentIdr = currentIdr || nalType == 5
            }
        }
        if (currentHasVcl) result += FixtureAccessUnit(currentIdr, current.toByteArray())
        return result
    }
}
