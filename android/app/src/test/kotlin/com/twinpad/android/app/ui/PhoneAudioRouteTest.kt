package com.twinpad.android.app.ui

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0208 — CR-115: con una salida Bluetooth conectada, el audio de la
 * sesión se reproduce en el altavoz del teléfono para que el PC no actúe
 * como bocina; sin Bluetooth se deja la salida del sistema.
 */
class PhoneAudioRouteTest {
    @Test
    fun bluetooth_output_pins_the_session_audio_to_the_phone_speaker() {
        assertEquals(
            PlaybackRoute.BUILTIN_SPEAKER,
            playbackRoute(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                ),
            ),
        )
        assertEquals(
            PlaybackRoute.BUILTIN_SPEAKER,
            playbackRoute(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)),
        )
    }

    @Test
    fun without_bluetooth_the_system_default_is_kept() {
        assertEquals(
            PlaybackRoute.SYSTEM_DEFAULT,
            playbackRoute(
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                ),
            ),
        )
        assertEquals(PlaybackRoute.SYSTEM_DEFAULT, playbackRoute(emptyList()))
    }
}
