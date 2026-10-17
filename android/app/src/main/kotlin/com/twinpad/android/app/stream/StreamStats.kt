package com.twinpad.android.app.stream

/** Ventana de cálculo del HUD (ms). */
private const val STAT_WINDOW_MS: Long = 1_000L

/** Ventana para estimar FPS sin caer a 0 entre keyframes de una imagen estática. */
private const val FPS_SAMPLE_WINDOW_MS: Long = 2_000L

/** Conversiones de unidades. */
private const val BITS_PER_BYTE: Double = 8.0
private const val KILO: Double = 1_000.0
private const val NANOS_PER_MILLI: Long = 1_000_000L
private const val MICROS_PER_MILLI: Double = 1_000.0
private const val MILLI_FPS_SCALE: Double = 1_000.0
private const val NANOS_PER_MICRO: Long = 1_000L

/** Conteo separado de imágenes recibidas y bitmaps efectivamente asignados a UI. */
class StreamStats(
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val traceCounter: (String, Long) -> Unit = VideoTrace::counter,
) {
    private val lock = Any()
    private val receivedAt = java.util.ArrayDeque<Long>()
    private val presentedAt = java.util.ArrayDeque<Long>()
    private var bytesWindow = 0L
    private var audioBytesWindow = 0L
    private var bitrateWindowStartMs: Long? = null
    private var frameNumberGaps = 0L
    private var rejectedFrames = 0L
    private var lastReceivedFrameNumber: Long? = null

    /** Decode/composition etapa acumulada desde la última observación. */
    private val stageNanos = LongArray(VideoStage.entries.size)
    private val stageSamples = LongArray(VideoStage.entries.size)

    enum class VideoStage {
        LINE_TO_DECODE,
        CODEC,
        JPEG_DECODE,
        TILE_COMPOSE,
        UI_PUBLISH_TO_DRAW,
    }

    data class Snapshot(
        val receiveFps: Double,
        val presentedFps: Double,
        val kbps: Double,
        val frameNumberGaps: Long,
        val rejectedFrames: Long,
        val stageAverageMs: Map<VideoStage, Double>,
    )

    /** FPS efectivos asignados a la ImageView. */
    var fps: Double = 0.0
        private set

    /** FPS de mensajes imagen que llegan/aplican, para diagnóstico. */
    var receiveFps: Double = 0.0
        private set

    /** Tasa combinada de video y audio recibida. */
    var kbps: Double = 0.0
        private set

    fun onImageReceived(
        frameNumber: Long,
        wireBytes: Int,
    ) {
        synchronized(lock) {
            val now = clockMs()
            if (lastReceivedFrameNumber?.let { frameNumber <= it } == true) {
                rejectedFrames++
                traceCounter("video.rejected_frames", rejectedFrames)
                return
            }
            receivedAt.addLast(now)
            lastReceivedFrameNumber?.let { previous ->
                if (frameNumber > previous + 1) frameNumberGaps += frameNumber - previous - 1
            }
            lastReceivedFrameNumber = frameNumber
            bytesWindow += wireBytes
            tickLocked(now)
        }
    }

    fun onFrameRejected() {
        synchronized(lock) {
            rejectedFrames++
            traceCounter("video.rejected_frames", rejectedFrames)
        }
    }

    fun recordStage(
        stage: VideoStage,
        elapsedNanos: Long,
    ) {
        synchronized(lock) {
            stageNanos[stage.ordinal] += elapsedNanos
            stageSamples[stage.ordinal]++
            traceCounter("video.stage_${stage.name.lowercase()}_us", elapsedNanos / NANOS_PER_MICRO)
        }
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                receiveFps = receiveFps,
                presentedFps = fps,
                kbps = kbps,
                frameNumberGaps = frameNumberGaps,
                rejectedFrames = rejectedFrames,
                stageAverageMs =
                    VideoStage.entries.associateWith { stage ->
                        val samples = stageSamples[stage.ordinal]
                        if (samples == 0L) {
                            0.0
                        } else {
                            stageNanos[stage.ordinal] / samples /
                                (NANOS_PER_MILLI / NANOS_PER_MICRO) / MICROS_PER_MILLI
                        }
                    },
            )
        }

    fun onFramePresented(): Long {
        synchronized(lock) {
            val now = clockMs()
            presentedAt.addLast(now)
            tickLocked(now)
            traceCounter("video.receive_fps_milli", (receiveFps * MILLI_FPS_SCALE).toLong())
            traceCounter("video.presented_fps_milli", (fps * MILLI_FPS_SCALE).toLong())
            return System.nanoTime()
        }
    }

    /** Compatibilidad para call sites que reciben y presentan en el mismo paso. */
    fun onFrame(wireBytes: Int) {
        synchronized(lock) {
            val now = clockMs()
            receivedAt.addLast(now)
            presentedAt.addLast(now)
            bytesWindow += wireBytes
            tickLocked(now)
        }
    }

    fun onAudio(wireBytes: Int) {
        synchronized(lock) {
            val now = clockMs()
            audioBytesWindow += wireBytes
            tickLocked(now)
        }
    }

    fun refresh() {
        synchronized(lock) {
            tickLocked(clockMs())
        }
    }

    fun reset() {
        synchronized(lock) {
            receivedAt.clear()
            presentedAt.clear()
            bytesWindow = 0
            audioBytesWindow = 0
            bitrateWindowStartMs = null
            fps = 0.0
            receiveFps = 0.0
            kbps = 0.0
            frameNumberGaps = 0
            rejectedFrames = 0
            lastReceivedFrameNumber = null
            stageNanos.fill(0)
            stageSamples.fill(0)
        }
    }

    private fun tickLocked(now: Long) {
        receiveFps = sampleRate(receivedAt, now, receiveFps)
        fps = sampleRate(presentedAt, now, fps)

        val windowStart = bitrateWindowStartMs
        if (windowStart == null) {
            bitrateWindowStartMs = now
            return
        }
        val elapsed = now - windowStart
        if (elapsed >= STAT_WINDOW_MS) {
            val seconds = elapsed / STAT_WINDOW_MS.toDouble()
            kbps = ((bytesWindow + audioBytesWindow) * BITS_PER_BYTE) / seconds / KILO
            bytesWindow = 0
            audioBytesWindow = 0
            bitrateWindowStartMs = now
        }
    }

    private fun sampleRate(
        samples: java.util.ArrayDeque<Long>,
        now: Long,
        previousRate: Double,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val cutoff = now - FPS_SAMPLE_WINDOW_MS
        while (samples.size > 1 && samples.first() < cutoff) samples.removeFirst()
        val first = samples.first()
        val last = samples.last()
        return when {
            now - last > FPS_SAMPLE_WINDOW_MS -> 0.0
            samples.size > 1 && last > first ->
                (samples.size - 1).toDouble() * STAT_WINDOW_MS.toDouble() / (last - first)
            else -> previousRate
        }
    }
}
