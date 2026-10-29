package com.twinpad.android.app.wire

import java.util.Base64

/** Geometría fija de la cámara del teléfono enviada a Cemu (CR-034; paridad con `core::game`). */
const val CAMERA_WIDTH: Int = 320
const val CAMERA_HEIGHT: Int = 240

/**
 * Codificador de `camera_frame` (SPEC-006 §4, CR-034). Una línea NDJSON por
 * access unit H.264 de la cámara frontal; `data` es base64url sin relleno.
 */
object CameraFrameCodec {
    fun encode(
        tsCaptureUs: Long,
        idr: Boolean,
        accessUnit: ByteArray,
    ): String {
        require(accessUnit.isNotEmpty()) { "camera_frame sin datos" }
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(accessUnit)
        return "{\"type\":\"camera_frame\",\"w\":$CAMERA_WIDTH,\"h\":$CAMERA_HEIGHT," +
            "\"ts_capture_us\":$tsCaptureUs,\"idr\":$idr,\"data\":\"$data\"}"
    }
}
