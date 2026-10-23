package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-080 — verifies SPEC-004: Android forwards the displayed absolute orientation unchanged. */
class ProfileGyroControllerTest {
    @Test
    fun publishes_absolute_screen_orientation_without_calibration_offset() {
        val reports = mutableListOf<List<Int>>()
        val controller = ProfileGyroController { reports.add(it) }

        controller.update(listOf(0, 0, 0))
        controller.update(listOf(-7_375, -331, 12))

        assertEquals(listOf(listOf(0, 0, 0), listOf(-7_375, -331, 12)), reports)
    }
}
