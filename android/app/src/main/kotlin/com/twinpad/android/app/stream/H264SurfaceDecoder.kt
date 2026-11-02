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

/** MediaCodec AVC decoder feeding the app-owned SurfaceView. */
class H264SurfaceDecoder(
    private val surface: Surface,
    private val onFramePresented: (Long) -> Unit,
) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val released = AtomicBoolean(false)
    private var configured = false
    private var waitingForIdr = true
    private var lastQueuedFrameNumber: Long? = null
    private val frameNumbersByTimestamp = HashMap<Long, Long>()

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
        lastQueuedFrameNumber = null
        frameNumbersByTimestamp.clear()
        arrivalNsByTimestamp.clear()
        lastArrivalNs = 0L
        lastPresentNs = 0L
        emaIntervalNs = EMA_INTERVAL_DEFAULT_NS
    }

    @Synchronized
    fun queue(unit: H264MediaClient.EncodedAccessUnit) {
        if (!isReadyForInput()) return
        val lastFrame = lastQueuedFrameNumber
        if (lastFrame != null && unit.frameNumber != lastFrame + 1L) {
            codec.flush()
            waitingForIdr = true
            lastQueuedFrameNumber = null
        }
        if (waitingForIdr && !unit.idr) return
        val index = codec.dequeueInputBuffer(0)
        if (index < 0 || !queueInputBuffer(index, unit)) {
            resetForDiscontinuity()
            return
        }
        frameNumbersByTimestamp[unit.timestampUs] = unit.frameNumber
        val arrivalNs = System.nanoTime()
        arrivalNsByTimestamp[unit.timestampUs] = arrivalNs
        if (lastArrivalNs != 0L) {
            val interval = (arrivalNs - lastArrivalNs).coerceIn(EMA_INTERVAL_MIN_NS, EMA_INTERVAL_MAX_NS)
            emaIntervalNs += (interval - emaIntervalNs) / EMA_ALPHA_DIVISOR
        }
        lastArrivalNs = arrivalNs
        lastQueuedFrameNumber = unit.frameNumber
        if (unit.idr) waitingForIdr = false
        drainOutput()
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
        lastQueuedFrameNumber = null
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
