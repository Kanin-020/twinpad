package com.twinpad.android.app.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

private const val MIN_FRAME_DIMENSION = 16
private const val MAX_FRAME_WIDTH = 3840
private const val MAX_FRAME_HEIGHT = 2160
private const val MAX_INPUT_BYTES = 4 * 1024 * 1024
private const val LOW_LATENCY_ENABLED = 1

// CR-048: jars frame pacing de presentación. La red entrega los frames en
// ráfagas aunque su cadencia media sea estable; presentar back-to-back
// produce tirones. EMA del intervalo de llegada y ancla de presentación:
// los buffers adelantados se difieren al próximo slot vía
// releaseOutputBuffer(index, renderTimestampNs) y los atrasados resincronizan
// el ancla adjacente al reloj actual. Solo usa el reloj del teléfono (CR-029
// intacto: nada de metadata de fps del wire).
private const val EMA_INTERVAL_DEFAULT_NS = 16_666_667L
private const val EMA_INTERVAL_MIN_NS = 4_000_000L
private const val EMA_INTERVAL_MAX_NS = 100_000_000L
private const val PRESENT_EARLY_NS = 2_000_000L

/** α del EMA del intervalo (1/4: reacciona lento a ráfagas aisladas). */
private const val EMA_ALPHA_DIVISOR = 4

/**
 * Holgura interna de AU pendientes de invertir en el codec. La red bajo WiFi
 * entrega las AU en ráfagas: si `dequeueInputBuffer` no tenía buffer libre en
 * ese instante, el flush imediato forzaba esperar la IDR periódica del
 * encoder (≈1 s de imagen congelada) — exactamente el microcorte descrito
 * por el owner. El backlog absorbe la ráfaga sin descartar AU consecutivas;
 * el reset ante stall genuino queda intacto como fallback (SPEC-003).
 */
private const val BACKLOG_MAX_UNITS = 64

/** MediaCodec AVC decoder feeding the app-owned SurfaceView. */
class H264SurfaceDecoder(
    private val surface: Surface,
    private val onFramePresented: (Long) -> Unit,
) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val released = AtomicBoolean(false)
    private var configured = false
    private var waitingForIdr = true

    /** Último fn admitido por orden de llegada (detección de hueco §14). */
    private var lastReceivedFrameNumber: Long? = null

    /** Último fn invertido en `codec` (orden FIFO del backlog). */
    private var lastQueuedFrameNumber: Long? = null
    private val frameNumbersByTimestamp = HashMap<Long, Long>()

    /** Backlog interno: AU válidas esperando buffer de entrada del codec. */
    private val pendingUnits = ArrayDeque<H264MediaClient.EncodedAccessUnit>()

    // CR-048: llegadas de AU y estado del pacing de presentación.
    private val arrivalNsByTimestamp = HashMap<Long, Long>()
    private var lastArrivalNs = 0L
    private var emaIntervalNs = EMA_INTERVAL_DEFAULT_NS
    private var lastPresentNs = 0L
    private var width = 0
    private var height = 0

    @Synchronized
    fun configure(
        frameWidth: Int,
        frameHeight: Int,
    ) {
        check(!released.get())
        require(frameWidth in MIN_FRAME_DIMENSION..MAX_FRAME_WIDTH)
        require(frameHeight in MIN_FRAME_DIMENSION..MAX_FRAME_HEIGHT)
        // CR-029: sin frameRate objetivo; el decoder consume AU al ritmo real.
        val configurationMatches =
            configured && width == frameWidth && height == frameHeight
        if (configurationMatches) return
        if (configured) codec.stop()
        width = frameWidth
        height = frameHeight
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, LOW_LATENCY_ENABLED)
        codec.configure(format, surface, null, 0)
        codec.start()
        configured = true
        waitingForIdr = true
        lastReceivedFrameNumber = null
        lastQueuedFrameNumber = null
        pendingUnits.clear()
        frameNumbersByTimestamp.clear()
        arrivalNsByTimestamp.clear()
        lastArrivalNs = 0L
        lastPresentNs = 0L
        emaIntervalNs = EMA_INTERVAL_DEFAULT_NS
    }

    /** internal: expone EncodedAccessUnit de H264MediaClient (visibilidad de paquete stream). */
    @Synchronized
    internal fun queue(unit: H264MediaClient.EncodedAccessUnit) {
        if (!isReadyForInput()) return
        val lastFrame = lastReceivedFrameNumber
        if (lastFrame != null && unit.frameNumber != lastFrame + 1L) {
            // Hueco §14: suspende la entrada hasta el próximo IDR SIN
            // `codec.flush()`. Las AU ya dentro del codec son del tramo
            // previo al hueco (cadena intacta, §5: contenido válido) y
            // siguen presentándose; solo se descartan las AU nuevas hasta
            // el IDR. Esto evita el reinicio destructivo del codec y el
            // churn de instancias (mId) durante pérdida breve.
            waitingForIdr = true
            pendingUnits.clear()
            // El hueco se marca consumido con fn actual: los P siguientes
            // son secuenciales y no re-disparan esta rama; su descarte
            // sigue a cargo del gate de abajo (§5: hasta IDR).
            lastReceivedFrameNumber = unit.frameNumber
            lastQueuedFrameNumber = null
        }
        if (waitingForIdr && !unit.idr) {
            // Gate §5: hasta IDR, descarte pasivo (sin tocar el codec).
            return
        }
        if (pendingUnits.size >= BACKLOG_MAX_UNITS) {
            // Stall genuino sostenido (el codec nunca libera entrada durante
            // BACKLOG_MAX_UNITS): cae al reset de §14 (conserva última imagen).
            resetForDiscontinuity()
            return
        }
        val arrivalNs = System.nanoTime()
        arrivalNsByTimestamp[unit.timestampUs] = arrivalNs
        if (lastArrivalNs != 0L) {
            val interval = (arrivalNs - lastArrivalNs).coerceIn(EMA_INTERVAL_MIN_NS, EMA_INTERVAL_MAX_NS)
            emaIntervalNs += (interval - emaIntervalNs) / EMA_ALPHA_DIVISOR
        }
        lastArrivalNs = arrivalNs
        lastReceivedFrameNumber = unit.frameNumber
        // El IDR encabeza el FIFO: al habilitarlo, las P que siguen ya son
        // válidas mientras no se inviertan (mismo orden de llegada).
        if (unit.idr) {
            waitingForIdr = false
        }
        pendingUnits.addLast(unit)
        feedBacklog()
    }

    /** Invierte en el codec tantas AU del backlog como buffers de entrada
     * libere; sin datos no hace nada (cola sigue esperando, nunca descarta). */
    private fun feedBacklog() {
        while (pendingUnits.isNotEmpty()) {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) {
                // Sin buffer libre en este instante: el backlog monta la
                // ráfaga y reintenta con la siguiente llegada/salida drenada.
                return
            }
            val unit = pendingUnits.first()
            if (!queueInputBuffer(index, unit)) {
                resetForDiscontinuity()
                return
            }
            pendingUnits.removeFirst()
            frameNumbersByTimestamp[unit.timestampUs] = unit.frameNumber
            lastQueuedFrameNumber = unit.frameNumber
            drainOutput()
        }
    }

    private fun isReadyForInput(): Boolean = !released.get() && configured

    private fun queueInputBuffer(
        index: Int,
        unit: H264MediaClient.EncodedAccessUnit,
    ): Boolean {
        val input = codec.getInputBuffer(index) ?: return false
        if (unit.bytes.size > input.capacity()) return false
        input.clear()
        input.put(unit.bytes)
        codec.queueInputBuffer(index, 0, unit.bytes.size, unit.timestampUs, 0)
        return true
    }

    @Synchronized
    fun resetForDiscontinuity() {
        if (released.get() || !configured) return
        codec.flush()
        waitingForIdr = true
        lastReceivedFrameNumber = null
        lastQueuedFrameNumber = null
        pendingUnits.clear()
        frameNumbersByTimestamp.clear()
        arrivalNsByTimestamp.clear()
        lastArrivalNs = 0L
        lastPresentNs = 0L
    }

    private fun drainOutput() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val frameNumber = frameNumbersByTimestamp.remove(info.presentationTimeUs)
            val arrivalNs = arrivalNsByTimestamp.remove(info.presentationTimeUs)
            val nowNs = System.nanoTime()
            if (lastPresentNs != 0L && nowNs < lastPresentNs + emaIntervalNs - PRESENT_EARLY_NS) {
                // CR-048: buffer adelantado respecto del ritmo medido; se
                // difiere su presentación al próximo slot para que los frames
                // que llegaron juntos no se vuelquen en un mismo vsync.
                codec.releaseOutputBuffer(index, lastPresentNs + emaIntervalNs)
                lastPresentNs += emaIntervalNs
            } else {
                codec.releaseOutputBuffer(index, true)
                // Presentación inmediata bajo el ritmo medido o retraso (
                // resync al reloj actual, nunca acumulativa).
                if (frameNumber != null) lastPresentNs = nowNs
            }
            // Surface decoders may report size=0 for rendered output buffers;
            // the matching presentation timestamp is the reliable signal.
            if (frameNumber != null) onFramePresented(frameNumber)
        }
    }

    @Synchronized
    override fun close() {
        if (!released.compareAndSet(false, true)) return
        if (configured) {
            runCatching { codec.stop() }
            configured = false
        }
        runCatching { codec.release() }
    }
}
