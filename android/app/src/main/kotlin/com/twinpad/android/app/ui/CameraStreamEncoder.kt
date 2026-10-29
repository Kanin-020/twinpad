package com.twinpad.android.app.ui

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.twinpad.android.app.wire.CAMERA_HEIGHT
import com.twinpad.android.app.wire.CAMERA_WIDTH
import com.twinpad.android.app.wire.CameraFrameCodec

private const val CAMERA_FPS = 30
private const val CAMERA_BITRATE_BPS = 500_000
private const val CAMERA_I_FRAME_INTERVAL_S = 1
private const val CAMERA_DEQUEUE_TIMEOUT_US = 10_000L

/**
 * Codifica la cámara frontal a H.264 320x240 / 30 fps (CR-034). La superficie
 * de entrada se entrega a CameraX como destino de Preview; cada access unit se
 * emite por [onFrame] como línea `camera_frame` lista para el canal de sesión.
 */
class CameraStreamEncoder(
    private val onFrame: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    private var config: ByteArray = ByteArray(0)

    /** Crea el codec y devuelve la superficie de entrada para CameraX. */
    fun start(): Surface {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, CAMERA_WIDTH, CAMERA_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, CAMERA_BITRATE_BPS)
                setInteger(MediaFormat.KEY_FRAME_RATE, CAMERA_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, CAMERA_I_FRAME_INTERVAL_S)
            }
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = created.createInputSurface()
        codec = created
        running = true
        created.start()
        worker = Thread({ drain(created) }, "camera-encoder").apply { start() }
        return surface
    }

    /** Detiene el codec y libera recursos. Idempotente. */
    fun stop() {
        running = false
        worker?.join(STOP_JOIN_MS)
        worker = null
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
        config = ByteArray(0)
    }

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = encoder.dequeueOutputBuffer(info, CAMERA_DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                emitOutput(encoder, index, info)
            }
        }
    }

    private fun emitOutput(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        val buffer = encoder.getOutputBuffer(index)
        val bytes = ByteArray(info.size)
        buffer?.position(info.offset)
        buffer?.limit(info.offset + info.size)
        buffer?.get(bytes)
        encoder.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = bytes
        } else if (bytes.isNotEmpty()) {
            val idr = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val unit = if (idr) config + bytes else bytes
            onFrame(CameraFrameCodec.encode(info.presentationTimeUs, idr, unit))
        }
    }

    private companion object {
        const val STOP_JOIN_MS = 500L
    }
}
