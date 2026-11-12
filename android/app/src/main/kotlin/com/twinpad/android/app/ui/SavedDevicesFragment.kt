package com.twinpad.android.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.twinpad.android.app.R

/**
 * CR-097: lista de conexiones guardadas. PairingActivity sigue siendo dueña de
 * la lógica (renderSavedDevices); este fragmento sólo infla
 * fragment_saved_devices.xml dentro de su contenedor.
 */
class SavedDevicesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_saved_devices, container, false)
}
