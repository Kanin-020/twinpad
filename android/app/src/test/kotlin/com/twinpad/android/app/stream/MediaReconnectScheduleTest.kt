package com.twinpad.android.app.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0224 — CR-127 (SPEC-003 §6/§8.2): la recuperación del canal media no
 * caduca. Reintento denso 250 ms → 2 s durante los primeros 10 s desde la
 * pérdida y, después, un intento cada 10 s mientras el `stream_start` siga
 * vigente (antes se abandonaba y el video no volvía).
 */
class MediaReconnectScheduleTest {
    @Test
    fun denseBackoffGrowsToTwoSecondsAndStaysCapped() {
        assertEquals(250L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 0L, attempt = 0))
        assertEquals(500L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 250L, attempt = 1))
        assertEquals(1_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 750L, attempt = 2))
        assertEquals(2_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 1_750L, attempt = 3))
        assertEquals(2_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 9_999L, attempt = 9))
    }

    @Test
    fun denseWindowBoundaryKeepsDenseUntilTenSeconds() {
        assertEquals(2_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 9_999L, attempt = 4))
        assertEquals(10_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 10_000L, attempt = 4))
    }

    @Test
    fun afterDenseWindowKeepsRetryingEveryTenSeconds() {
        assertEquals(10_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 10_000L, attempt = 0))
        assertEquals(10_000L, MediaReconnectSchedule.delayMs(elapsedSinceLossMs = 3_600_000L, attempt = 500))
    }
}
