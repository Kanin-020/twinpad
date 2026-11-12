package com.twinpad.android.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.twinpad.android.app.R

/**
 * CR-097 paso 3: vista de sesión (vídeo, métricas, HUD, teclado, ajustes).
 * PairingActivity sigue siendo dueña de la lógica; este fragmento sólo infla
 * fragment_session.xml dentro de su contenedor y avisa cuando la vista existe.
 */
class SessionFragment(
    private val onSessionReady: () -> Unit,
) : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_session, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onSessionReady()
    }
}
