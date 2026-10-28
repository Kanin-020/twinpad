package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** TEST-095 — verifies SPEC-006 (CR-034): `camera_frame` carries 320x240 H.264 access units. */
class CameraFrameCodecTest {
    @Test
    fun camera_frame_encodes_geometry_timestamp_idr_and_base64url_payload() {
        val au = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
        val line = CameraFrameCodec.encode(33_000L, true, au)

        assertTrue(line.startsWith("{\"type\":\"camera_frame\",\"w\":320,\"h\":240,"))
        assertTrue(line.contains("\"ts_capture_us\":33000"))
        assertTrue(line.contains("\"idr\":true"))
        val data = line.substringAfter("\"data\":\"").substringBefore("\"}")
        assertTrue(!data.contains("="))
        assertEquals(au.toList(), Base64.getUrlDecoder().decode(data).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun camera_frame_rejects_empty_access_unit() {
        CameraFrameCodec.encode(0L, false, ByteArray(0))
    }
}
