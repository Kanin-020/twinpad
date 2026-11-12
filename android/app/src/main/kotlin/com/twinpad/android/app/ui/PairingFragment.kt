package com.twinpad.android.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.twinpad.android.app.R

/**
 * CR-097 paso 1: panel de conexión (escaneo, cámara, estado, botones).
 * PairingActivity sigue siendo dueña de la lógica y de las vistas; este
 * fragmento sólo infla fragment_pairing.xml dentro de su contenedor y avisa
 * a la Activity cuando la vista ya existe.
 */
class PairingFragment(
    private val onPanelReady: () -> Unit,
) : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_pairing, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onPanelReady()
    }
}
