package com.twinpad.android.app.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import androidx.core.view.isVisible
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel

/**
 * CR-097: alterna entre la vista de conexión nueva y la lista de conexiones
 * guardadas, desde las pestañas y desde el drawer. Mantiene el estado de la
 * vista activa para que [PairingActivity] no lo tenga que cargar.
 */
internal class ConnectionTabsController(
    private val activity: Activity,
    private val viewModel: PairingViewModel,
    private val panelView: () -> View,
    private val renderState: (PairingUiState) -> Unit,
) {
    /** CR-097: el usuario pidió la vista de conexión con una sesión viva. */
    var showingConnectionView = false
        private set

    /** CR-097 v2: cambia entre nueva conexión y lista desde las pestañas. */
    fun show(connectionView: Boolean) {
        showingConnectionView = connectionView
        renderState(viewModel.uiState.value)
        updateDrawerButton()
    }

    /** CR-097: el botón del drawer alterna entre ambas vistas. */
    fun toggleFromDrawer() {
        showingConnectionView = !showingConnectionView
        renderState(viewModel.uiState.value)
        updateDrawerButton()
    }

    /** Muestra una sola vista de conexión: sesión, panel o lista guardada. */
    fun renderSwitch(
        state: PairingUiState,
        sessionLinked: Boolean,
    ) {
        // Las pestañas solo aparecen fuera de la sesión (con sesión viva usa el drawer).
        activity.findViewById<View>(R.id.view_tabs).isVisible = state !is PairingUiState.Linked
        activity.findViewById<Button>(R.id.btn_tab_new).isEnabled = showingConnectionView
        activity.findViewById<Button>(R.id.btn_tab_list).isEnabled = !showingConnectionView
        // CR-097: el drawer alterna entre el panel de conexión y la lista de
        // conexiones en cualquier estado; cada una ocupa su propia vista.
        activity.findViewById<View>(R.id.btn_drawer_connection).isVisible = true
        val showSession = sessionLinked && !showingConnectionView
        val showList = !showSession && showingConnectionView
        val showPanel = !showSession && !showingConnectionView
        activity.findViewById<View>(R.id.session_container).isVisible = showSession
        activity.findViewById<View>(R.id.saved_devices_container).isVisible = showList
        panelView().isVisible = showPanel
    }

    private fun updateDrawerButton() {
        activity.findViewById<Button>(R.id.btn_drawer_connection).setText(
            if (showingConnectionView) R.string.settings_session_view else R.string.settings_connection_view,
        )
    }
}
