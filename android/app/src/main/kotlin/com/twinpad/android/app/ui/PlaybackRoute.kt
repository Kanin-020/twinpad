package com.twinpad.android.app.ui

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** CR-115: salida que debe usar el audio de Twinpad mientras el teléfono
 * tiene una salida Bluetooth conectada. Con BT el PC puede reproducir lo que
 * suena en el teléfono (bocina), así que el stream de la sesión se fija al
 * altavoz del propio teléfono y no sale por esa ruta. */
internal enum class PlaybackRoute { SYSTEM_DEFAULT, BUILTIN_SPEAKER }

/** Decide la salida de reproducción a partir de los tipos de salida activos. */
internal fun playbackRoute(outputTypes: List<Int>): PlaybackRoute =
    if (outputTypes.any(::isBluetoothAudioOutputType)) {
        PlaybackRoute.BUILTIN_SPEAKER
    } else {
        PlaybackRoute.SYSTEM_DEFAULT
    }

/** Altavoz integrado del teléfono, si el sistema lo expone como salida. */
internal fun builtinSpeaker(audioManager: AudioManager): AudioDeviceInfo? =
    audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

/** Aplica a la reproducción de la sesión la ruta vigente (CR-115). */
internal fun applyPhoneAudioRoute(
    audioManager: AudioManager,
    setOutputDevice: (AudioDeviceInfo?) -> Unit,
) {
    val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    setOutputDevice(
        when (playbackRoute(types)) {
            PlaybackRoute.BUILTIN_SPEAKER -> builtinSpeaker(audioManager)
            PlaybackRoute.SYSTEM_DEFAULT -> null
        },
    )
}
