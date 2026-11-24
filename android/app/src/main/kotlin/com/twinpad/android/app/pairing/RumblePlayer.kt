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
    /** Vibrador por defecto del sistema; null en entornos sin vibrator service. */
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    /** Sincroniza el patrón del mensaje con una única onda (nada de cola:
     * el último mensaje de rumble manda, CR-033 §3). */
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

    /** Vibración continua por BT (CR-109): la intensidad máxima de strong/weak
     * (0…255) manda; 0 detiene el motor. */
    fun playStrength(
        strong: Int,
        weak: Int,
    ) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val amplitude = maxOf(strong, weak).coerceIn(0, MAX_AMPLITUDE)
        if (amplitude == 0) {
            device.cancel()
            return
        }
        device.vibrate(VibrationEffect.createOneShot(BT_RUMBLE_MS, amplitude))
    }

    private companion object {
        private const val MAX_AMPLITUDE = 255
        private const val BT_RUMBLE_MS = 100L
    }
}
