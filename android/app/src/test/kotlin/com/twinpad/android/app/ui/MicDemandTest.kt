package com.twinpad.android.app.ui

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEST-0206 — la captura del micrófono requiere juego seleccionado, demanda
 * `mic_request`, interruptor del drawer y NINGUNA salida Bluetooth conectada
 * (CR-115: con BT el PC actúa como bocina del teléfono).
 */
class MicDemandTest {
    @Test
    fun capture_requires_every_gate() {
        assertEquals(false, micCaptureAllowed(false, true, true, false))
        assertEquals(false, micCaptureAllowed(true, false, true, false))
        assertEquals(false, micCaptureAllowed(true, true, false, false))
        assertEquals(true, micCaptureAllowed(true, true, true, false))
    }

    @Test
    fun bluetooth_audio_route_blocks_the_capture() {
        assertEquals(false, micCaptureAllowed(true, true, true, true))
    }

    @Test
    fun bluetooth_output_types_are_the_audio_ones() {
        assertEquals(true, isBluetoothAudioOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(true, isBluetoothAudioOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(true, isBluetoothAudioOutputType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(true, isBluetoothAudioOutputType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        // La superficie del mando (BT HID, CR-096) no es una salida de audio.
        assertEquals(false, isBluetoothAudioOutputType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(false, isBluetoothAudioOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}
