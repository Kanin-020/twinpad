package com.twinpad.android.app.ui

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.chip.ChipGroup
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Las tres vistas que alterna el menú de navegación del drawer. */
internal enum class ConnectionView { SESSION, NEW_CONNECTION, SAVED_CONNECTIONS }

/** Contenido de la vista de sesión según la máquina (CR-135). */
internal enum class SessionPane { CONTENT, RECONNECTING, EMPTY }

/**
 * CR-135: la reconexión con la PC es parte de la sesión, no del panel de
 * vinculación: con la sesión retenida (U6) la vista de sesión muestra el loader.
 */
internal fun resolveSessionPane(
    sessionAlive: Boolean,
    reconnecting: Boolean,
): SessionPane =
    when {
        !sessionAlive -> SessionPane.EMPTY
        reconnecting -> SessionPane.RECONNECTING
        else -> SessionPane.CONTENT
    }

/**
 * Vista visible según la selección del menú. La vista de sesión no depende de
 * que haya sesión vinculada: sin sesión muestra su propio estado vacío y nunca
 * el panel de vinculación (CR-133).
 */
internal fun resolveConnectionView(
    showingSession: Boolean,
    showingSavedConnections: Boolean,
): ConnectionView =
    when {
        showingSession -> ConnectionView.SESSION
        showingSavedConnections -> ConnectionView.SAVED_CONNECTIONS
        else -> ConnectionView.NEW_CONNECTION
    }

/**
 * CR-138: sesión que la lista de conexiones guardadas marca como activa. U4 y
 * U6 cuentan (la sesión sigue viva mientras se reconecta); el indicador es
 * independiente de que la transmisión esté en marcha.
 */
internal fun activeSavedSessionId(state: PairingUiState): String? =
    when (state) {
        is PairingUiState.Linked -> state.sessionId
        is PairingUiState.Reconnecting -> state.sessionId
        else -> null
    }

/**
 * CR-108/CR-134: registrado un enlace pendiente, cierra la cámara del escaneo
 * y vuelve a la vista de sesión; el controlador de pestañas avisa del enlace.
 */
internal fun observePendingLink(
    scope: CoroutineScope,
    pendingLinkWaiting: StateFlow<Boolean>,
    stopScan: () -> Unit,
    showSession: () -> Unit,
) {
    scope.launch {
        pendingLinkWaiting.collectLatest { waiting ->
            if (waiting) {
                stopScan()
                showSession()
            }
        }
    }
}

/**
 * CR-097/CR-133: alterna entre la sesión actual, la nueva conexión (QR) y la
 * lista de conexiones guardadas, desde los chips del drawer.
 */
internal class ConnectionTabsController(
    private val activity: Activity,
    private val viewModel: PairingViewModel,
    private val panelView: () -> View,
    private val renderState: (PairingUiState) -> Unit,
) {
    /** `true` cuando se muestra la lista de conexiones guardadas. */
    var showingConnectionView = false
        private set

    /** `true` cuando la vista elegida es la sesión, haya sesión viva o no. */
    var showingSession = false
        private set

    /** Última vez que había sesión: al vincular de nuevo vuelve a la sesión. */
    private var wasLinked = false

    /** Último valor de enlace pendiente ya atendido (CR-134). */
    private var wasPendingLink = false

    /** Cambia entre nueva conexión y lista desde los chips del drawer. */
    fun show(connectionView: Boolean) {
        showingConnectionView = connectionView
        showingSession = false
        renderState(viewModel.uiState.value)
    }

    /** Muestra la vista de sesión desde el chip «Sesión actual». */
    fun showSession() {
        showingConnectionView = false
        showingSession = true
        renderState(viewModel.uiState.value)
    }

    /** Muestra una sola vista: sesión, su estado vacío, lista o panel. */
    fun renderSwitch(state: PairingUiState) {
        // U6 reconectando todavía es sesión viva: la vista de sesión muestra el
        // loader de reconexión. Al perderla se vuelve al panel, que es donde
        // SPEC-002 §9 muestra el error y «Reintentar».
        val sessionAlive = state is PairingUiState.Linked || state is PairingUiState.Reconnecting
        if (sessionAlive && !wasLinked) showingSession = true
        if (!sessionAlive && wasLinked) showingSession = false
        wasLinked = sessionAlive
        // CR-134: con la sesión corriendo, registrar otro enlace devuelve el menú
        // a «Sesión actual» una sola vez y avisa; no queda esperando en el panel.
        val pending = viewModel.pendingLinkWaiting.value
        if (pending && !wasPendingLink) {
            if (sessionAlive) showingSession = true
            Toast.makeText(activity, R.string.pairing_pending_link, Toast.LENGTH_LONG).show()
        }
        wasPendingLink = pending
        val visible = resolveConnectionView(showingSession, showingConnectionView)
        val pane =
            resolveSessionPane(
                sessionAlive = sessionAlive,
                reconnecting = state is PairingUiState.Reconnecting,
            )
        // CR-127: el contenedor del video se mantiene con la sesión retenida
        // para no destruir la Surface; el loader se pinta encima.
        activity.findViewById<View>(R.id.session_container).isVisible =
            visible == ConnectionView.SESSION && sessionAlive
        // CR-133: sin sesión viva la vista de sesión conserva su propio
        // contenido vacío; el panel de vinculación queda solo en su vista.
        activity.findViewById<View>(R.id.session_empty_container).isVisible =
            visible == ConnectionView.SESSION && pane == SessionPane.EMPTY
        // CR-135: la reconexión vive en la vista de sesión.
        activity.findViewById<View>(R.id.session_reconnect_container).isVisible =
            visible == ConnectionView.SESSION && pane == SessionPane.RECONNECTING
        activity.findViewById<View>(R.id.saved_devices_container).isVisible =
            visible == ConnectionView.SAVED_CONNECTIONS
        val showPanel = visible == ConnectionView.NEW_CONNECTION
        activity.findViewById<View>(R.id.pairing_fragment_container).isVisible = showPanel
        panelView().isVisible = showPanel
        updateNavigationItem(visible)
    }

    /** Marca en el menú de navegación la vista activa. */
    private fun updateNavigationItem(visible: ConnectionView) {
        activity.findViewById<ChipGroup>(R.id.navigation_view).check(
            when (visible) {
                ConnectionView.SESSION -> R.id.nav_session
                ConnectionView.SAVED_CONNECTIONS -> R.id.nav_connection_list
                ConnectionView.NEW_CONNECTION -> R.id.nav_new_connection
            },
        )
    }
}
