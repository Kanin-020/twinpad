package com.twinpad.android.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Report ID + 4 sticks + 2 triggers + hat/botones + botones altos + pad + batería.
        assertEquals(11, report.size)
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
    fun buttonsMapToStandardHidUsages() {
        // SPEC-004 §4: bit 0 = button_a … bit 11 = extra_2; el report map
        // declara Usage 1…15, así que A/B/X/Y van a 1/2/4/5 (no a 1/2/3/4)
        // para que el SO no lea X como "C" ni Y como "X".
        val buttons = (1 shl 0) or (1 shl 3) or (1 shl 4) or (1 shl 11)
        val report = SignatureHidCodec.encode(0, 0, 0, 0, 8, buttons, 0, 0)
        // A→h0, extra_2→h5, L1→h6, Y→h4: hid = 0x71; byte 7 = hatch + h0…h3.
        assertEquals(0x0F or 0x10, report[7].toInt() and 0xFF)
        assertEquals(0x07, report[8].toInt() and 0xFF)
        assertEquals(0x00, report[9].toInt() and 0xFF)

        // L3/R3 (bits 6/7 canónicos) → Usage 14/15 = h13/h14, bits 1 y 2 del byte 9.
        val thumb = SignatureHidCodec.encode(0, 0, 0, 0, 8, (1 shl 6) or (1 shl 7), 0, 0)
        assertEquals(0x06, thumb[9].toInt() and 0xFF)

        // Home (bit 10 canónico) → Usage 13 = Guide/PS (h12, bit 0 del byte 9),
        // igual que el reporte BT de Xbox y el DualShock 4; no un botón de cara.
        val home = SignatureHidCodec.encode(0, 0, 0, 0, 8, 1 shl 10, 0, 0)
        assertEquals(0x01, home[9].toInt() and 0xFF)
        assertEquals(0x00, home[7].toInt() and 0xF0)
        assertEquals(0x00, home[8].toInt() and 0xFF)

        // Start (bit 8) y Select (bit 9) → Usage 12/11 = h11/h10 (byte 8).
        val start = SignatureHidCodec.encode(0, 0, 0, 0, 8, 1 shl 8, 0, 0)
        assertEquals(0x80, start[8].toInt() and 0xFF)
        val select = SignatureHidCodec.encode(0, 0, 0, 0, 8, 1 shl 9, 0, 0)
        assertEquals(0x40, select[8].toInt() and 0xFF)
    }

    @Test
    fun batteryEncodesPercentInByte10AndClampsTo100() {
        val half = SignatureHidCodec.encode(0, 0, 0, 0, 8, 0, 0, 0, batteryPercent = 50)
        assertEquals(50.toByte(), half[10])
        val over = SignatureHidCodec.encode(0, 0, 0, 0, 8, 0, 0, 0, batteryPercent = 180)
        assertEquals(100.toByte(), over[10])
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

    @Test
    fun reportMapDeclaresInputAndRumbleReportsOnly() {
        // CR-111: BT lleva report 1 (controles) y report 3 (vibración); gyro/touch van por LAN.
        val map = SignatureHidCodec.REPORT_MAP
        assertTrue(containsSequence(map, byteArrayOf(0x85.toByte(), 0x01)))
        assertTrue(containsSequence(map, byteArrayOf(0x85.toByte(), 0x03)))
        assertFalse(containsSequence(map, byteArrayOf(0x85.toByte(), 0x02)))
        // Salida de vibración: Output (0x91) para los 2 bytes.
        assertTrue(containsSequence(map, byteArrayOf(0x91.toByte(), 0x02)))
    }

    private fun containsSequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
