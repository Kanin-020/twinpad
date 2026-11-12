package com.twinpad.android.app.ui

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel

/**
 * CR-097: pinta la lista de conexiones guardadas dentro de
 * `saved_devices_container` (fragment_saved_devices.xml). Mantiene la fila
 * expandida; las acciones delegan en el [PairingViewModel] y piden un nuevo
 * render con [rerender].
 */
internal class SavedDevicesRenderer(
    private val activity: PairingActivity,
    private val container: View,
    private val viewModel: PairingViewModel,
    private val rerender: () -> Unit,
) {
    private var expandedSavedId: String? = null

    fun render(state: PairingUiState) {
        val list = container.findViewById<LinearLayout>(R.id.saved_devices_list) ?: return
        val saved = viewModel.savedLinks()
        val activeId =
            when (state) {
                is PairingUiState.Linked -> state.sessionId
                is PairingUiState.Reconnecting -> state.sessionId
                else -> null
            }
        list.removeAllViews()
        if (saved.isEmpty()) {
            list.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.pairing_saved_empty)
                    setTextColor(activity.getColor(R.color.design_quiet_slate))
                },
            )
            return
        }
        saved.forEach { link ->
            val isActive = link.sessionId == activeId
            list.addView(rowButton(link.name.ifBlank { link.addr }, link.sessionId, isActive))
            if (link.sessionId == expandedSavedId) {
                list.addView(actionButton(link.sessionId, isActive))
            }
        }
    }

    private fun rowButton(
        label: String,
        sessionId: String,
        isActive: Boolean,
    ): Button =
        Button(activity).apply {
            text = if (isActive) "\u25cf $label" else label
            if (isActive) setTextColor(activity.getColor(R.color.metrics_ring_ram))
            setOnClickListener {
                expandedSavedId = if (expandedSavedId == sessionId) null else sessionId
                rerender()
            }
        }

    /** CR-097: con la conexión activa, Desconectar aparece en su fila;
     * las inactivas ofrecen Olvidar. */
    private fun actionButton(
        sessionId: String,
        isActive: Boolean,
    ): Button =
        Button(activity).apply {
            text =
                activity.getString(
                    if (isActive) R.string.pairing_saved_disconnect else R.string.pairing_saved_forget,
                )
            setOnClickListener {
                expandedSavedId = null
                if (isActive) {
                    viewModel.onDisconnectRequested()
                } else {
                    viewModel.forgetSavedLink(sessionId)
                }
                rerender()
            }
        }
}
