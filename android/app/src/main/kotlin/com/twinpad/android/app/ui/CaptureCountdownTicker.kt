package com.twinpad.android.app.ui

import android.os.Handler
import android.os.Looper
import com.twinpad.android.app.hid.CaptureCountdown

/**
 * CR-124 (SPEC-011 §6 M3): cadencia de un segundo del contador de la captura.
 * La espera de 10 s se reinicia con cada entrada recibida; cuando se agota,
 * [onExpired] cierra la captura sin cambiar el mapeo.
 */
internal class CaptureCountdownTicker(
    private val onSecond: () -> Unit,
    private val onExpired: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val countdown = CaptureCountdown()

    /** Segundos que quedan, para el texto del estado. */
    val remaining: Int get() = countdown.remaining

    private val tick =
        object : Runnable {
            override fun run() {
                if (countdown.tick()) {
                    onExpired()
                } else {
                    onSecond()
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }

    /** Empieza (o reinicia) la espera. */
    fun restart() {
        countdown.restart()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    /** Detiene la espera y la deja lista para la próxima captura. */
    fun stop() {
        handler.removeCallbacks(tick)
        countdown.restart()
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
