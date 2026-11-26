package com.twinpad.android.hid

import android.bluetooth.BluetoothClass

/**
 * CR-122: orden y filtrado de los emparejados clásicos al conectar el canal
 * BT HID. Funciones puras para poder probarlas sin Bluetooth (TEST-0215/0216).
 */
internal object HidHostOrder {
    /** Clase mayor Bluetooth de un equipo de audio (coche, auriculares). */
    private val AUDIO_VIDEO_MAJOR = BluetoothClass.Device.Major.AUDIO_VIDEO

    /**
     * CR-122: un host HID de gamepad es un PC (clase MISC/Computer/Peripheral);
     * un equipo AUDIO_VIDEO (radio del coche, auriculares) jamás acepta el
     * enlace HID y cada intento cuesta un page timeout de ~5 s en la ronda.
     */
    fun isLikelyHost(majorDeviceClass: Int): Boolean = majorDeviceClass != AUDIO_VIDEO_MAJOR

    /**
     * Pone primero [lastUsed] —el host que conectó la última vez— y deja el
     * resto en el orden de los emparejados. Si no hay host recordado (o ya no
     * está emparejado), el orden queda intacto.
     */
    fun order(
        addresses: List<String>,
        lastUsed: String?,
    ): List<String> = if (lastUsed == null) addresses else addresses.sortedBy { if (it == lastUsed) 0 else 1 }
}
