package com.twinpad.android.hid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST-0218 — CR-125: una ronda de conexión HID colgada vence a los 10 s. */
class HidConnectWatchdogTest {
    @Test
    fun `armed round expires only after the timeout`() {
        val watchdog = HidConnectWatchdog()
        watchdog.arm(nowMs = 1_000L)

        assertFalse(watchdog.expireIfDue(nowMs = 10_999L))
        assertTrue(watchdog.expireIfDue(nowMs = 11_000L))
    }

    @Test
    fun `expiry is reported once`() {
        val watchdog = HidConnectWatchdog()
        watchdog.arm(nowMs = 0L)

        assertTrue(watchdog.expireIfDue(nowMs = 10_000L))
        assertFalse(watchdog.expireIfDue(nowMs = 20_000L))
    }

    @Test
    fun `resolved round never expires`() {
        val watchdog = HidConnectWatchdog()
        watchdog.arm(nowMs = 0L)
        watchdog.resolve()

        assertFalse(watchdog.expireIfDue(nowMs = 60_000L))
    }

    @Test
    fun `new round restarts the window`() {
        val watchdog = HidConnectWatchdog()
        watchdog.arm(nowMs = 0L)
        watchdog.arm(nowMs = 8_000L)

        assertFalse(watchdog.expireIfDue(nowMs = 17_999L))
        assertTrue(watchdog.expireIfDue(nowMs = 18_000L))
    }
}
