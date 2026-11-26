package com.twinpad.android.app.stream

/**
 * Cadencia de reconexión del canal media (SPEC-003 §6/§8.2, CR-127): densa
 * (250 ms → 2 s) durante los primeros 10 s desde la pérdida y espaciada
 * (10 s) después. La recuperación no caduca mientras el `stream_start`
 * siga vigente; el canal solo se abandona con `stream_stop` o al cerrar el
 * Pairing. Antes, a los 10 s se borraban dimensiones y `stream_start`, así
 * que el teléfono dejaba de conectar y el video no volvía nunca.
 */
internal object MediaReconnectSchedule {
    /** Ventana de reintento denso desde la pérdida del canal. */
    const val DENSE_WINDOW_MS = 10_000L

    /** Primer reintento tras perder el canal. */
    const val INITIAL_DELAY_MS = 250L

    /** Techo del backoff dentro de la ventana densa. */
    const val MAX_DENSE_DELAY_MS = 2_000L

    /** Cadencia del reintento espaciado una vez vencida la ventana densa. */
    const val IDLE_DELAY_MS = 10_000L

    /** Espera tras el intento [attempt] (0-based) a [elapsedSinceLossMs] de la pérdida. */
    fun delayMs(
        elapsedSinceLossMs: Long,
        attempt: Int,
    ): Long =
        if (elapsedSinceLossMs >= DENSE_WINDOW_MS) {
            IDLE_DELAY_MS
        } else {
            (INITIAL_DELAY_MS shl attempt.coerceIn(0, MAX_DENSE_SHIFT))
                .coerceAtMost(MAX_DENSE_DELAY_MS)
        }

    /** 250 << 3 = 2 000 ms: a partir del cuarto intento el backoff ya está en el techo. */
    private const val MAX_DENSE_SHIFT = 3
}
