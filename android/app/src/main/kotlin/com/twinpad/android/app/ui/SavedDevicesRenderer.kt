package com.twinpad.android.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
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
            list.addView(linkCard(link.name.ifBlank { link.addr }, link.addr, link.sessionId, isActive))
        }
    }

    /** Card Material de una conexión guardada; al tocarla despliega su acción. */
    private fun linkCard(
        label: String,
        addr: String,
        sessionId: String,
        isActive: Boolean,
    ): View {
        val card =
            LayoutInflater.from(activity).inflate(R.layout.item_saved_device, container as ViewGroup, false)
                as MaterialCardView
        card.findViewById<TextView>(R.id.saved_device_name).text = if (isActive) "\u25cf $label" else label
        card.findViewById<TextView>(R.id.saved_device_addr).text = addr
        val action = card.findViewById<Button>(R.id.saved_device_action)
        action.visibility = if (sessionId == expandedSavedId) View.VISIBLE else View.GONE
        action.text =
            activity.getString(
                if (isActive) R.string.pairing_saved_disconnect else R.string.pairing_saved_forget,
            )
        action.setOnClickListener {
            expandedSavedId = null
            if (isActive) {
                viewModel.onDisconnectRequested()
            } else {
                viewModel.forgetSavedLink(sessionId)
            }
            rerender()
        }
        card.setOnClickListener {
            expandedSavedId = if (expandedSavedId == sessionId) null else sessionId
            rerender()
        }
        return card
    }

}
