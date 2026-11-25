package com.twinpad.android.app.ui

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * CR-115: con audio Bluetooth conectado, el PC reproduce como bocina el audio
 * del teléfono, así que la captura del micrófono no debe enviarse. La decisión
 * vive en una función pura para poder probarla sin Android.
 */
internal fun micCaptureAllowed(
    gameSelected: Boolean,
    micRequested: Boolean,
    switchEnabled: Boolean,
    bluetoothAudioConnected: Boolean,
): Boolean = gameSelected && micRequested && switchEnabled && !bluetoothAudioConnected

/** Salidas Bluetooth que hacen que la PC actúe como bocina del teléfono:
 * A2DP, manos libres (SCO) y LE Audio (auricular y altavoz). */
internal fun isBluetoothAudioOutputType(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER

/** `true` si el teléfono tiene hoy una salida de audio Bluetooth conectada. */
internal fun hasBluetoothAudioRoute(audioManager: AudioManager): Boolean =
    audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .any { isBluetoothAudioOutputType(it.type) }
