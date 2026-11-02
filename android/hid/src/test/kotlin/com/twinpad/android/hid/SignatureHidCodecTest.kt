package com.twinpad.android.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0126 — CR-059/ADR-009: codificación de la Signature HID (SPEC-004 §2/§4)
 * al reporte HID del gamepad por Bluetooth y ausencia de posición de
 * bytes desalineada con el report map descripto.
 */
class SignatureHidCodecTest {
    @Test
    fun restStateEncodesToNeutralReport() {
        // Sticks en reposo, D-pad neutro de la Signature, sin botones ni triggers.
        val report = SignatureHidCodec.encode(0, 0, 0, 0, 8, 0, 0, 0)
        // Report ID + 4 sticks + 2 triggers + hat/botones + botones altos + pad.
        assertEquals(10, report.size)
        assertEquals(1.toByte(), report[0]) // Report ID
        assertEquals(0.toByte(), report[1]) // LX
        assertEquals(0.toByte(), report[2]) // LY
        assertEquals(0x0F.toByte(), report[7]) // hat neutro vía Null State
        assertEquals(0.toByte(), report[8]) // botones b4…b11
        assertEquals(0.toByte(), report[9]) // pad
    }

    @Test
    fun sticksClampToSignatureRangeAndEncodeSigned() {
        // −200 → −127 (clamp §9); 5 → 5; el límite inferior exacto queda.
        val negative = SignatureHidCodec.encode(-200, 5, 127, -128, 0, 0, 0, 0)
        assertEquals((-127).toByte(), negative[1])
        assertEquals(5.toByte(), negative[2])
        assertEquals(127.toByte(), negative[3])
        assertEquals((-127).toByte(), negative[4]) // −128 se clampea a −127
    }

    @Test
    fun dpadDirectionsKeepIdentityAndNeutralUsesNullState() {
        val up = SignatureHidCodec.encode(0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0x00, up[7].toInt() and 0x0F)
        val direction3 = SignatureHidCodec.encode(0, 0, 0, 0, 3, 0, 0, 0)
        assertEquals(0x03, direction3[7].toInt() and 0x0F)
        val neutral = SignatureHidCodec.encode(0, 0, 0, 0, 8, 0, 0, 0)
        assertEquals(0x0F, neutral[7].toInt() and 0x0F)
    }

    @Test
    fun buttonsFillBits4to15AsMergedNibbles() {
        // Botones según SPEC-004 §4: bit 0 = button_a … bit 11 = extra_2.
        val buttons = (1 shl 0) or (1 shl 3) or (1 shl 4) or (1 shl 11)
        val report = SignatureHidCodec.encode(0, 0, 0, 0, 8, buttons, 0, 0)
        // byte 7 bits 4…7 = b0…b3 → b0 y b3 = 0x01 + 0x08 = 0x90 (hat neutro 0x0F)
        assertEquals((0x0F or 0x90), report[7].toInt() and 0xFF)
        // byte 8 bits 0…7 = b4…b11 → b4 y b11 = 0x01 + 0x80 = 0x81
        assertEquals(0x81, report[8].toInt() and 0xFF)
    }

    @Test
    fun triggersClampTo255() {
        val report = SignatureHidCodec.encode(0, 0, 0, 0, 8, 0, 300, 128)
        assertEquals(255.toByte(), report[5])
        assertEquals(128.toByte(), report[6])
    }

    @Test
    fun reportMapMatchesDeclaredLayoutAndEndsCollection() {
        val map = SignatureHidCodec.REPORT_MAP
        assertTrue(map.isNotEmpty())
        // El descriptor abre con Usage Page(0x05) y cierra End Collection (0xC0).
        assertEquals(0x05.toByte(), map[0])
        assertEquals(0xC0.toByte(), map[map.size - 1])
        // Report ID 1 declarado dentro del Application collection.
        assertTrue(map.take(map.size - 1).contains(0x85.toByte()))
        // El gamepad usa Usage Page Button (0x05 0x09) y deriva Hat (0x39).
        var hasButtonPage = false
        for (i in 0 until map.size - 1) {
            if (map[i] == 0x05.toByte() && map[i + 1] == 0x09.toByte()) hasButtonPage = true
        }
        val hasHat = map.contains(0x39.toByte())
        assertTrue(hasButtonPage)
        assertTrue(hasHat)
    }
}
