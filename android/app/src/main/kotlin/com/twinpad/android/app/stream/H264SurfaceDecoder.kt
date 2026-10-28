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
    }

    private fun drainOutput() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            codec.releaseOutputBuffer(index, true)
            val frameNumber = frameNumbersByTimestamp.remove(info.presentationTimeUs)
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
