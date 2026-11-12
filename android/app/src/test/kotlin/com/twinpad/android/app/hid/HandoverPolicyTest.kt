package com.twinpad.android.app.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0127 — CR-098 (revierte CR-096): el gamepad sale por la LAN cuando la
 * sesión está Linked; el canal Bluetooth HID es respaldo (SPEC-004 §8.3).
 */
class HandoverPolicyTest {
    @Test
    fun lanLinkedRoutesToLan() {
        assertEquals(InputTransport.LAN, HandoverPolicy.route(lanLinked = true, btReady = true))
        assertEquals(InputTransport.LAN, HandoverPolicy.route(lanLinked = true, btReady = false))
    }

    @Test
    fun lanDownWithBtRoutesToBluetooth() {
        assertEquals(InputTransport.BT, HandoverPolicy.route(lanLinked = false, btReady = true))
    }

    @Test
    fun noRouteIsH0NoSend() {
        assertEquals(InputTransport.NONE, HandoverPolicy.route(lanLinked = false, btReady = false))
    }
}
