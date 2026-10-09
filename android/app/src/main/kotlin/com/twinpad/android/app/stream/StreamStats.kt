package com.twinpad.android.app.stream

/** Ventana de cálculo del HUD: 1 s (detekt: sin números mágicos). */
private const val STAT_WINDOW_MS: Long = 1_000L

/** Factor byte → bit. */
private const val BITS_PER_BYTE: Double = 8.0

/** Factor kilo (bits → kbits). */
private const val KILO: Double = 1_000.0

/**
 * Medición del stream para el HUD (fps y tasa de transferencia). Puro y
 * thread-safe: el lector de sesión incrementa; la UI consulta cada tick.
 */
class StreamStats {
    private val lock = Any()

    /** Frames aceptados desde la última consulta (ventana de 1 s). */
    private var framesWindow = 0
    private var bytesWindow = 0L
    private var windowStartMs = 0L
    private var audioBytesWindow = 0L

    /** Últimos valores calculados (visibles para la UI). */
    var fps: Double = 0.0
        private set
    var kbps: Double = 0.0
        private set

    /** Registra un frame aceptado con su tamaño wire (línea NDJSON). */
    fun onFrame(wireBytes: Int) {
        synchronized(lock) {
            framesWindow++
            bytesWindow += wireBytes
            tickLocked()
        }
    }

    /** Registra bytes de audio (para la tasa total). */
    fun onAudio(wireBytes: Int) {
        synchronized(lock) {
            audioBytesWindow += wireBytes
            tickLocked()
        }
    }

    /** Reinicia (nueva sesión). */
    fun reset() {
        synchronized(lock) {
            framesWindow = 0
            bytesWindow = 0
            audioBytesWindow = 0
            windowStartMs = 0
            fps = 0.0
            kbps = 0.0
        }
    }

    private fun tickLocked() {
        val now = System.currentTimeMillis()
        if (windowStartMs == 0L) {
            windowStartMs = now
            return
        }
        val elapsed = now - windowStartMs
        if (elapsed >= STAT_WINDOW_MS) {
            val secs = elapsed / STAT_WINDOW_MS.toDouble()
            fps = framesWindow / secs
            kbps = ((bytesWindow + audioBytesWindow) * BITS_PER_BYTE) / secs / KILO
            framesWindow = 0
            bytesWindow = 0
            audioBytesWindow = 0
            windowStartMs = now
        }
    }
}
