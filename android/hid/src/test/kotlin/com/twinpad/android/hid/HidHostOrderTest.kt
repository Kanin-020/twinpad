package com.twinpad.android.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST-0215 — CR-122: el host que conectó la última vez se intenta primero. */
class HidHostOrderTest {
    @Test
    fun `last used host goes first`() {
        val bonded = listOf("AA", "BB", "CC")

        assertEquals(listOf("CC", "AA", "BB"), HidHostOrder.order(bonded, lastUsed = "CC"))
    }

    @Test
    fun `without a remembered host the bonded order is kept`() {
        val bonded = listOf("AA", "BB")

        assertEquals(bonded, HidHostOrder.order(bonded, lastUsed = null))
    }

    @Test
    fun `remembered host no longer paired keeps the bonded order`() {
        val bonded = listOf("AA", "BB")

        assertEquals(bonded, HidHostOrder.order(bonded, lastUsed = "ZZ"))
    }

    /** TEST-0216 — CR-122: los equipos de audio no son hosts del gamepad. */
    @Test
    fun `audio devices are not candidates for the hid channel`() {
        val audioMajor = 0x0400 // BluetoothClass.Device.Major.AUDIO_VIDEO

        assertTrue(!HidHostOrder.isLikelyHost(audioMajor))
        assertTrue(HidHostOrder.isLikelyHost(0x0000)) // MISC (adaptador BlueZ del PC)
        assertTrue(HidHostOrder.isLikelyHost(0x0100)) // COMPUTER
        assertTrue(HidHostOrder.isLikelyHost(0x0500)) // PERIPHERAL
    }
}
