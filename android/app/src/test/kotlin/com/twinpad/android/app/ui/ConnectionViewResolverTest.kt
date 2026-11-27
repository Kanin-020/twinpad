package com.twinpad.android.app.ui

import com.twinpad.android.app.core.PairingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST-0232 — verifies CR-133: la vista de sesión y el panel de vinculación son
 * vistas separadas. Sin sesión viva, elegir «Sesión actual» resuelve a la
 * vista de sesión (con su estado vacío) y nunca al panel de QR/código.
 *
 * TEST-0234 — verifies CR-135: la reconexión (U6) es contenido de la sesión
 * (loader), no del panel de vinculación; sin sesión la vista queda vacía.
 *
 * TEST-0236 — verifies CR-138: la lista de guardados marca la conexión activa
 * en U4 y U6 con independencia de que la transmisión esté en marcha.
 */
class ConnectionViewResolverTest {
    @Test
    fun `session view is selected without a linked session`() {
        assertEquals(
            ConnectionView.SESSION,
            resolveConnectionView(showingSession = true, showingSavedConnections = false),
        )
    }

    @Test
    fun `pairing panel is the default view`() {
        assertEquals(
            ConnectionView.NEW_CONNECTION,
            resolveConnectionView(showingSession = false, showingSavedConnections = false),
        )
    }

    @Test
    fun `saved connections view is selected only when no session view is requested`() {
        assertEquals(
            ConnectionView.SAVED_CONNECTIONS,
            resolveConnectionView(showingSession = false, showingSavedConnections = true),
        )
        assertEquals(
            ConnectionView.SESSION,
            resolveConnectionView(showingSession = true, showingSavedConnections = true),
        )
    }

    @Test
    fun `reconnecting session shows the reconnect loader`() {
        assertEquals(SessionPane.RECONNECTING, resolveSessionPane(sessionAlive = true, reconnecting = true))
    }

    @Test
    fun `linked session shows its content`() {
        assertEquals(SessionPane.CONTENT, resolveSessionPane(sessionAlive = true, reconnecting = false))
    }

    @Test
    fun `no session shows the empty state even while a reconnect is pending`() {
        assertEquals(SessionPane.EMPTY, resolveSessionPane(sessionAlive = false, reconnecting = true))
    }

    @Test
    fun `saved list marks the linked and reconnecting session as active`() {
        assertEquals("s1", activeSavedSessionId(PairingUiState.Linked("s1")))
        assertEquals("s1", activeSavedSessionId(PairingUiState.Reconnecting("s1")))
        assertNull(activeSavedSessionId(PairingUiState.Idle))
        assertNull(activeSavedSessionId(PairingUiState.Scanning))
    }
}
