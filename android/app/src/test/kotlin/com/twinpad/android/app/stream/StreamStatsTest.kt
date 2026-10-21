package com.twinpad.android.app.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-030 — verifies SPEC-003 monitoring: HUD counts presented frames, not just received messages. */
class StreamStatsTest {
    // TEST-034 — verifies SPEC-003 performance diagnostics for queue, stage timing and sequence gaps.
    @Test
    fun records_frame_gaps_dropped_work_and_stage_durations() {
        var now = 1_000L
        val stats = StreamStats(clockMs = { now }, traceCounter = { _, _ -> })
        stats.onImageReceived(10, 2_000)
        stats.onImageReceived(12, 2_500)
        stats.recordStage(StreamStats.VideoStage.CODEC, 3_000_000L)
        stats.recordStage(StreamStats.VideoStage.CODEC, 5_000_000L)
        stats.onFrameRejected()
        now += 16L
        stats.onFramePresented(1)

        val snapshot = stats.snapshot()
        assertEquals(1L, snapshot.frameNumberGaps)
        assertEquals(1L, snapshot.lastPresentedFrameNumber)
        assertEquals(1L, snapshot.rejectedFrames)
        assertEquals(4.0, snapshot.stageAverageMs[StreamStats.VideoStage.CODEC]!!, 0.001)
        assertEquals(6, snapshot.stageAverageMs.size)
    }

    // TEST-030 regression: a new stream restarts frame numbers at zero; only video sequence stats reset.
    @Test
    fun new_video_stream_resets_frame_number_epoch_without_resetting_audio_stats() {
        var now = 1_000L
        val stats = StreamStats(clockMs = { now }, traceCounter = { _, _ -> })
        stats.onImageReceived(101, 1_000)
        assertEquals(true, stats.onFramePresented(101))
        stats.onAudio(12_000)
        now += 1_000L
        stats.refresh()

        stats.resetVideoStream()
        stats.onImageReceived(0, 1_000)
        assertEquals(0L, stats.snapshot().rejectedFrames)
        assertEquals(true, stats.onFramePresented(0))
        assertEquals(0L, stats.snapshot().lastPresentedFrameNumber)
        assertEquals(96.0, stats.snapshot().audioKbps, 0.001)
    }

    @Test
    fun reports_presented_fps_separately_from_received_fps() {
        var now = 1_000L
        val stats = StreamStats(clockMs = { now }, traceCounter = { _, _ -> })

        repeat(60) { index ->
            now = 1_000L + index * 16L
            stats.onImageReceived(1_000L + index, 1_000)
            if (index % 2 == 0) stats.onFramePresented()
        }
        stats.refresh()

        assertEquals(62.5, stats.receiveFps, 0.1)
        assertEquals(31.25, stats.fps, 0.1)
    }

    @Test
    fun audio_rate_is_reported_separately_and_reset_with_stream_metrics() {
        var now = 2_000L
        val stats = StreamStats(clockMs = { now }, traceCounter = { _, _ -> })
        stats.onAudio(12_000)
        now += 1_000L
        stats.onMediaAccessUnitReceived(0, 2_000)
        val measured = stats.snapshot()
        assertEquals(96.0, measured.audioKbps, 0.001)
        assertEquals(112.0, measured.kbps, 0.001)

        stats.reset()
        assertEquals(0.0, stats.snapshot().audioKbps, 0.0)
        assertEquals(null, stats.snapshot().lastPresentedFrameNumber)
    }

    @Test
    fun single_static_keyframe_is_not_reported_as_zero_before_stale_timeout() {
        var now = 5_000L
        val stats = StreamStats(clockMs = { now }, traceCounter = { _, _ -> })
        stats.onImageReceived(4_096, 1_000)
        stats.onFramePresented()

        now += 1_000L
        stats.refresh()
        assertEquals(0.0, stats.receiveFps, 0.0)
        assertEquals(0.0, stats.fps, 0.0)

        now += 1_001L
        stats.refresh()
        assertEquals(0.0, stats.fps, 0.0)
    }

    @Test
    fun periodic_static_keyframes_report_their_presented_rate() {
        var now = 10_000L
        val stats = StreamStats(clockMs = { now }, traceCounter = { _, _ -> })
        stats.onImageReceived(1, 1_000)
        stats.onFramePresented()

        now += 1_000L
        stats.onImageReceived(2, 1_000)
        stats.onFramePresented()
        stats.refresh()

        assertEquals(1.0, stats.receiveFps, 0.01)
        assertEquals(1.0, stats.fps, 0.01)
    }
}
