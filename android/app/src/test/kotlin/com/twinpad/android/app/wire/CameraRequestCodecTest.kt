package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEST-099 — verifies SPEC-006 (CR-038): `camera_request` decoding is strict. */
class CameraRequestCodecTest {
    @Test
    fun decodes_on_and_off() {
        assertEquals(CameraRequestMessage(true), CameraRequestCodec.decode("""{"type":"camera_request","on":true}"""))
        assertEquals(CameraRequestMessage(false), CameraRequestCodec.decode("""{"type":"camera_request","on":false}"""))
    }

    @Test
    fun rejects_missing_flag_bad_flag_other_type_and_other_messages() {
        // Flag `on` faltante.
        assertNull(CameraRequestCodec.decode("""{"type":"camera_request"}"""))
        // Flag no booleano.
        assertNull(CameraRequestCodec.decode("""{"type":"camera_request","on":"yes"}"""))
        assertNull(CameraRequestCodec.decode("""{"type":"camera_request","on":1}"""))
        // Campo desconocido no es rechazado aquí (paridad con RumbleCodec);
        // el contrato estricto del JSON lo gobierna server/core.
        assertEquals(
            CameraRequestMessage(true),
            CameraRequestCodec.decode("""{"type":"camera_request","on":true,"x":1}"""),
        )
        // Otro tipo de mensaje.
        assertNull(CameraRequestCodec.decode("""{"type":"rumble","length":0,"data":""}"""))
        // Línea nula/corrupta.
        assertNull(CameraRequestCodec.decode("no-json"))
        assertNull(CameraRequestCodec.decode("[1,2,3]"))
    }
}
