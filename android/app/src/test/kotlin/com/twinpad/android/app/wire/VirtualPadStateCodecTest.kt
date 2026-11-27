package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST-0227 — CR-130: `virtual_pad_state` (SPEC-006 §4) se decodifica de
 * forma estricta: `type` y `on:boolean` exactamente, sin campos extra.
 */
class VirtualPadStateCodecTest {
    @Test
    fun decodesBothStates() {
        assertEquals(
            VirtualPadStateMessage(true),
            VirtualPadStateCodec.decode("""{"type":"virtual_pad_state","on":true}"""),
        )
        assertEquals(
            VirtualPadStateMessage(false),
            VirtualPadStateCodec.decode("""{"type":"virtual_pad_state","on":false}"""),
        )
    }

    @Test
    fun rejectsMissingOrNonBooleanFlag() {
        assertNull(VirtualPadStateCodec.decode("""{"type":"virtual_pad_state"}"""))
        assertNull(VirtualPadStateCodec.decode("""{"type":"virtual_pad_state","on":"true"}"""))
        assertNull(VirtualPadStateCodec.decode("""{"type":"virtual_pad_state","on":1}"""))
        assertNull(VirtualPadStateCodec.decode("""{"type":"virtual_pad_state","on":null}"""))
    }

    @Test
    fun rejectsExtraFieldsAndWrongType() {
        assertNull(
            VirtualPadStateCodec.decode(
                """{"type":"virtual_pad_state","on":true,"extra":false}""",
            ),
        )
        assertNull(VirtualPadStateCodec.decode("""{"type":"rumble","on":true}"""))
        assertNull(VirtualPadStateCodec.decode("""{"type":1,"on":true}"""))
        assertNull(VirtualPadStateCodec.decode("not-json"))
    }
}
