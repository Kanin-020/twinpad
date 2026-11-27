package com.twinpad.android.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0207 — CR-108: un enlace pedido con sesión activa se guarda y se
 * entrega una sola vez, cuando la sesión termina; la sesión viva no se toca.
 *
 * TEST-0233 — CR-134: lo mismo vale para una conexión guardada («Conectar»):
 * queda retenida mientras la sesión vive y se entrega al terminar; el último
 * enlace pedido sustituye al anterior.
 */
class PendingLinkTest {
    private val qr = "twinpad://v1#s=192.168.1.10&p=8422&t=abc"

    @Test
    fun keeps_the_payload_while_the_session_lives() {
        val pending = PendingLink()
        pending.saveQr(qr)
        assertTrue(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = true))
        assertTrue(pending.isWaiting)
    }

    @Test
    fun delivers_once_when_the_session_ends() {
        val pending = PendingLink()
        pending.saveQr(qr)
        assertEquals(PendingLinkRequest.Qr(qr), pending.takeWhenSessionEnded(sessionAlive = false))
        assertFalse(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = false))
    }

    @Test
    fun nothing_is_delivered_without_a_saved_payload() {
        val pending = PendingLink()
        assertFalse(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = false))
    }

    @Test
    fun keeps_a_saved_device_while_the_session_lives() {
        val pending = PendingLink()
        pending.saveSavedDevice("session-2")
        assertTrue(pending.isWaiting)
        assertEquals(null, pending.takeWhenSessionEnded(sessionAlive = true))
        assertEquals(
            PendingLinkRequest.SavedDevice("session-2"),
            pending.takeWhenSessionEnded(sessionAlive = false),
        )
        assertFalse(pending.isWaiting)
    }

    @Test
    fun the_last_request_replaces_the_previous_one() {
        val pending = PendingLink()
        pending.saveQr(qr)
        pending.saveSavedDevice("session-2")
        assertEquals(
            PendingLinkRequest.SavedDevice("session-2"),
            pending.takeWhenSessionEnded(sessionAlive = false),
        )
    }
}
