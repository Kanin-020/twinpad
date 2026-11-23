package com.twinpad.android.app.ui

import android.app.Activity
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.chip.ChipGroup
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel

/**
 * CR-097: alterna entre la sesión actual, la nueva conexión (QR) y la lista
 * de conexiones guardadas, desde las pestañas y desde el drawer.
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

    /** `true` cuando se muestra la sesión viva (por defecto al vincular). */
    var showingSession = true
        private set

    /** Última vez que había sesión: al vincular de nuevo vuelve a la sesión. */
    private var wasLinked = false

    /** Cambia entre nueva conexión y lista desde los segmented buttons y el drawer. */
    fun show(connectionView: Boolean) {
        showingConnectionView = connectionView
        showingSession = false
        renderState(viewModel.uiState.value)
    }

    /** Vuelve a la sesión viva desde el drawer (ítem "Sesión actual"). */
    fun showSession() {
        showingConnectionView = false
        showingSession = true
        renderState(viewModel.uiState.value)
    }

    /** Muestra una sola vista de conexión: sesión, panel o lista guardada. */
    fun renderSwitch(
        state: PairingUiState,
        sessionLinked: Boolean,
    ) {
        if (sessionLinked && !wasLinked) {
            showingSession = true
        }
        wasLinked = sessionLinked
        val showSession = sessionLinked && showingSession
        val showList = !showSession && showingConnectionView
        val showPanel = !showSession && !showingConnectionView
        activity.findViewById<View>(R.id.session_container).isVisible = showSession
        activity.findViewById<View>(R.id.saved_devices_container).isVisible = showList
        activity.findViewById<View>(R.id.pairing_fragment_container).isVisible = showPanel
        panelView().isVisible = showPanel
        updateNavigationItem(showSession)
    }

    /** Marca en el navigation drawer la vista activa. */
    private fun updateNavigationItem(showSession: Boolean) {
        activity.findViewById<ChipGroup>(R.id.navigation_view).check(
            when {
                showSession -> R.id.nav_session
                showingConnectionView -> R.id.nav_connection_list
                else -> R.id.nav_new_connection
            },
        )
    }
}
