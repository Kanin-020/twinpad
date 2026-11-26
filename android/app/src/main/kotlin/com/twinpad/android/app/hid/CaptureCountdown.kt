package com.twinpad.android.app.hid

/**
 * CR-124 (SPEC-011 §6 M3): cuenta atrás de la captura del mapeador. Si no
 * llega ninguna entrada en [DEFAULT_SECONDS] segundos, la fila abandona la
 * captura sin cambiar el mapeo. Sin dependencias de Android para poder
 * probarla en JVM.
 */
class CaptureCountdown(
    private val seconds: Int = DEFAULT_SECONDS,
) {
    /** Segundos que quedan para abandonar la captura. */
    var remaining: Int = seconds
        private set

    /** Vuelve a empezar la espera (al entrar en captura o al recibir input). */
    fun restart() {
        remaining = seconds
    }

    /** Consume un segundo; `true` cuando la captura se agota. */
    fun tick(): Boolean {
        if (remaining > 0) remaining -= 1
        return remaining <= 0
    }

    companion object {
        const val DEFAULT_SECONDS = 10
    }
}
