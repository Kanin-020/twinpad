package com.twinpad.android.app.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0127 — CR-059/ADR-009: política de handover del gamepad entre la
 * ruta LAN y la ruta Bluetooth HID (SPEC-004 §8.3): prioridad de la LAN,
 * falla al BT si el canal HID está operativo, histéresis al liberar.
 */
class HandoverPolicyTest {
    @Test
    fun linkedLanAlwaysRoutesToLan() {
        assertEquals(InputTransport.LAN, HandoverPolicy.route(lanLinked = true, btReady = true))
        assertEquals(InputTransport.LAN, HandoverPolicy.route(lanLinked = true, btReady = false))
    }

    @Test
    fun lanDownWithBtReadyFailsOverToBluetooth() {
        // Failover automático ante caída de la sesión LAN (HB5/expiry).
        assertEquals(InputTransport.BT, HandoverPolicy.route(lanLinked = false, btReady = true))
    }

    @Test
    fun lanDownWithoutBtIsH0NoSend() {
        assertEquals(InputTransport.NONE, HandoverPolicy.route(lanLinked = false, btReady = false))
    }

    @Test
    fun releaseHysteresisIsFiveSeconds() {
        assertEquals(5_000L, HandoverPolicy.BT_RELEASE_HYSTERESIS_MS)
    }
}
