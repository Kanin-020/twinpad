package com.twinpad.android.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** TEST-025 — verifies SPEC-003 R2/R3: stale queued frames avoid decode work. */
class StreamPipelineTest {
    @Test
    fun stale_frames_are_discarded_before_decode() {
        val decoded = AtomicInteger()
        val acceptedFrames = AtomicInteger()
        val staleFrameChecked = CountDownLatch(1)
        val decodedCurrentFrame = CountDownLatch(1)
        val pipeline =
            StreamPipeline(
                stats = StreamStats(),
                onFrame = { acceptedFrames.incrementAndGet() },
                isFrameCurrent = { frameNumber ->
                    if (frameNumber == 5L) staleFrameChecked.countDown()
                    frameNumber > 5L
                },
            )

        try {
            pipeline.submit(frameNumber = 5, wireBytes = 100) {
                decoded.incrementAndGet()
                null
            }
            assertTrue("stale frame should be checked", staleFrameChecked.await(2, TimeUnit.SECONDS))
            pipeline.submit(frameNumber = 6, wireBytes = 100) {
                decoded.incrementAndGet()
                decodedCurrentFrame.countDown()
                null
            }

            assertTrue("current frame should reach the decoder", decodedCurrentFrame.await(2, TimeUnit.SECONDS))
            awaitCondition { decoded.get() == 1 }
            assertEquals(1, decoded.get())
            assertEquals(0, acceptedFrames.get())
        } finally {
            pipeline.close()
        }
    }

    companion object {
        fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!condition() && System.nanoTime() < deadline) Thread.yield()
            assertTrue("condition should become true", condition())
        }
    }
}
