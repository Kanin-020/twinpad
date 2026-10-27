package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEST-086 — CR-025: `contain` conserva la relación de aspecto con barras; `expand` ocupa todo. */
class VideoScaleModeTest {
    private val epsilon = 1e-5f

    @Test
    fun expand_fills_the_whole_container() {
        val rect = videoContentRect(VideoScaleMode.EXPAND, 1000f, 500f, 1280, 720)

        assertRect(VideoContentRect(0f, 0f, 1f, 1f), rect)
    }

    @Test
    fun contain_letterboxes_a_wider_container_with_side_bars_when_video_is_4_3() {
        // Contenedor 2:1, video 4:3 → imagen centrada con barras a los lados.
        val rect = videoContentRect(VideoScaleMode.CONTAIN, 2000f, 1000f, 4, 3)

        assertRect(VideoContentRect(0.1666667f, 0f, 0.6666667f, 1f), rect)
    }

    @Test
    fun contain_letterboxes_a_taller_container_with_top_and_bottom_bars() {
        // Contenedor 1:1, video 16:9 → barras arriba y abajo.
        val rect = videoContentRect(VideoScaleMode.CONTAIN, 800f, 800f, 1920, 1080)

        assertRect(VideoContentRect(0f, 0.21875f, 1f, 0.5625f), rect)
    }

    @Test
    fun contain_with_matching_aspect_has_no_bars() {
        val rect = videoContentRect(VideoScaleMode.CONTAIN, 1280f, 720f, 960, 540)

        assertRect(VideoContentRect(0f, 0f, 1f, 1f), rect)
    }

    @Test
    fun unknown_sizes_fall_back_to_full_container() {
        assertRect(
            VideoContentRect(0f, 0f, 1f, 1f),
            videoContentRect(VideoScaleMode.CONTAIN, 0f, 500f, 1280, 720),
        )
        assertRect(
            VideoContentRect(0f, 0f, 1f, 1f),
            videoContentRect(VideoScaleMode.CONTAIN, 1000f, 500f, 0, 0),
        )
    }

    private fun assertRect(
        expected: VideoContentRect,
        actual: VideoContentRect,
    ) {
        assertEquals(expected.left, actual.left, epsilon)
        assertEquals(expected.top, actual.top, epsilon)
        assertEquals(expected.width, actual.width, epsilon)
        assertEquals(expected.height, actual.height, epsilon)
    }
}
