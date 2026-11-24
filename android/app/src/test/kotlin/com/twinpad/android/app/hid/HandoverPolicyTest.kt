package com.twinpad.android.app.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0127 — CR-111 (revierte CR-098): el gamepad sale por el canal Bluetooth
 * HID cuando está operativo (por defecto); la LAN es respaldo (SPEC-004 §8.3).
 */
class HandoverPolicyTest {
    @Test
    fun btReadyRoutesToBluetoothFirst() {
        assertEquals(InputTransport.BT, HandoverPolicy.route(lanLinked = true, btReady = true))
        assertEquals(InputTransport.BT, HandoverPolicy.route(lanLinked = false, btReady = true))
    }

    @Test
    fun btDownWithLanRoutesToLanFallback() {
        assertEquals(InputTransport.LAN, HandoverPolicy.route(lanLinked = true, btReady = false))
    }

    @Test
    fun noRouteIsH0NoSend() {
        assertEquals(InputTransport.NONE, HandoverPolicy.route(lanLinked = false, btReady = false))
    }
}
