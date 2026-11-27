package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST-0229 — CR-131: `rumble_motors` (SPEC-006 §4) se decodifica de forma
 * estricta: `strong`/`weak` enteros 0…255, sin campos extra.
 */
class RumbleMotorsCodecTest {
    @Test
    fun decodesMotorStrengthsIncludingStop() {
        assertEquals(
            RumbleMotorsMessage(strong = 128, weak = 32),
            RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":128,"weak":32}"""),
        )
        assertEquals(
            RumbleMotorsMessage(strong = 0, weak = 0),
            RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":0,"weak":0}"""),
        )
        assertEquals(
            RumbleMotorsMessage(strong = 255, weak = 255),
            RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":255,"weak":255}"""),
        )
    }

    @Test
    fun rejectsMissingNonIntegerOrOutOfRangeMotors() {
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":1}"""))
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":"1","weak":2}"""))
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":1.5,"weak":2}"""))
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":-1,"weak":2}"""))
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":256,"weak":2}"""))
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble_motors","strong":1,"weak":null}"""))
    }

    @Test
    fun rejectsExtraFieldsWrongTypeAndInvalidJson() {
        assertNull(
            RumbleMotorsCodec.decode(
                """{"type":"rumble_motors","strong":1,"weak":2,"extra":0}""",
            ),
        )
        assertNull(RumbleMotorsCodec.decode("""{"type":"rumble","strong":1,"weak":2}"""))
        assertNull(RumbleMotorsCodec.decode("""{"type":1,"strong":1,"weak":2}"""))
        assertNull(RumbleMotorsCodec.decode("not-json"))
    }
}
