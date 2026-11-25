package com.twinpad.android.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0207 — CR-108: un enlace pedido con sesión activa se guarda y se
 * entrega una sola vez, cuando la sesión termina; la sesión viva no se toca.
 */
class PendingLinkTest {
    @Test
    fun keeps_the_payload_while_the_session_lives() {
        val pending = PendingLink()
        pending.save("twinpad://pair?token=abc")
        assertTrue(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = true))
        assertTrue(pending.isWaiting)
    }

    @Test
    fun delivers_once_when_the_session_ends() {
        val pending = PendingLink()
        pending.save("twinpad://pair?token=abc")
        assertEquals("twinpad://pair?token=abc", pending.takeWhenSessionEnded(sessionAlive = false))
        assertFalse(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = false))
    }

    @Test
    fun nothing_is_delivered_without_a_saved_payload() {
        val pending = PendingLink()
        assertFalse(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = false))
    }
}
