package com.twinpad.android.app.ui

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * CR-035/CR-090/CR-115: decide y aplica la captura del micrófono del teléfono.
 * Gate completo: juego seleccionado, demanda `mic_request`, interruptor «Enviar
 * micrófono al GamePad» y NINGUNA salida de audio Bluetooth (con BT el PC
 * reproduce el audio del teléfono como si fuera una bocina). La ruta se
 * reevalúa en caliente cuando se conecta o desconecta un dispositivo BT.
 */
internal class MicDemandController(
    private val audioManager: AudioManager,
    private val mic: PairingMicController,
    private val gameSelected: () -> Boolean,
    private val micRequested: () -> Boolean,
    private val switchEnabled: () -> Boolean,
    private val requestPermission: () -> Unit,
) {
    private val devicesCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = apply()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = apply()
        }

    fun register() {
        audioManager.registerAudioDeviceCallback(devicesCallback, null)
    }

    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(devicesCallback)
    }

    /** Si falta el permiso RECORD_AUDIO se pide y el lanzador reevalúa. */
    fun apply() {
        val demand =
            micCaptureAllowed(
                gameSelected = gameSelected(),
                micRequested = micRequested(),
                switchEnabled = switchEnabled(),
                bluetoothAudioConnected = hasBluetoothAudioRoute(audioManager),
            )
        when {
            !demand -> mic.stop()
            mic.hasPermission() -> mic.start()
            else -> requestPermission()
        }
    }
}
