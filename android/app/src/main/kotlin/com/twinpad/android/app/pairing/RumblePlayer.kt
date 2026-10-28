package com.twinpad.android.app.pairing

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.twinpad.android.app.wire.RumbleCodec
import com.twinpad.android.app.wire.RumbleMessage

/**
 * Reproduce en el vibrador del teléfono los patrones de `VPADControlMotor`
 * de Cemu (CR-033). Sin vibrador, el mensaje se ignora.
 */
class RumblePlayer(context: Context) {
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    fun play(message: RumbleMessage) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        if (message.pattern.isEmpty()) {
            device.cancel()
            return
        }
        val waveform = RumbleCodec.waveform(message.pattern)
        device.vibrate(VibrationEffect.createWaveform(waveform.timings, waveform.amplitudes, -1))
    }
}
