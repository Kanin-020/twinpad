package com.twinpad.android.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** TEST-024 — verifies SPEC-005 §7: playback continues across stream changes. */
class AudioPlaybackTest {
    @Test
    fun chunks_continue_after_reconfigure_and_audio_stop() {
        val writes = AtomicInteger()
        val tracksCreated = AtomicInteger()
        val allWrites = CountDownLatch(4)
        val released = CountDownLatch(3)
        val volumeUpdates = AtomicInteger()
        val latestVolume = AtomicReference(1f)
        val playback =
            AudioPlayback(
                PlaybackTrackFactory { _, _ ->
                    tracksCreated.incrementAndGet()
                    object : PlaybackTrack {
                        override fun write(pcm: ByteArray) {
                            writes.incrementAndGet()
                            allWrites.countDown()
                        }

                        override fun setVolume(volume: Float) {
                            latestVolume.set(volume)
                            volumeUpdates.incrementAndGet()
                        }

                        override fun stopAndRelease() {
                            released.countDown()
                        }
                    }
                },
            )

        try {
            playback.drain(listOf(chunk(seq = 0, rate = 44_100)))
            playback.drain(listOf(chunk(seq = 1, rate = 44_100)))
            playback.setVolume(0.5f)
            playback.drain(listOf(chunk(seq = 2, rate = 48_000)))
            playback.stop()
            playback.drain(listOf(chunk(seq = 3, rate = 48_000)))

            assertTrue("all PCM chunks should reach the output", allWrites.await(2, TimeUnit.SECONDS))
            playback.release()
            assertTrue("each active track should be released", released.await(2, TimeUnit.SECONDS))
            assertEquals(4, writes.get())
            assertEquals(3, tracksCreated.get())
            assertTrue("volume should be applied to active tracks", volumeUpdates.get() >= 2)
            assertEquals(0.5f, latestVolume.get(), 0.001f)
        } finally {
            playback.release()
        }
    }

    private fun chunk(
        seq: Long,
        rate: Int,
    ) = AudioChunk(seq, byteArrayOf(1, 0), rate, channels = 1)
}
