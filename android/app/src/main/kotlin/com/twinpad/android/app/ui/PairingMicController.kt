package com.twinpad.android.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.twinpad.android.app.wire.MicChunkCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captura del micrófono del teléfono bajo demanda (CR-035): AudioRecord a
 * 32 kHz mono s16 con bloques de 480 muestras (15 ms) hacia `mic_chunk`.
 * El juego la enciende con `mic_request on=true` desde Cemu (MICOpen del
 * HLE `mic`) y sin demanda ni permiso RECORD_AUDIO no graba nada.
 */
class PairingMicController(
    private val context: Context,
    private val onChunk: (String) -> Unit,
) {
    /** Fragmentos de captura: 15 ms a 32 kHz (480 muestras). */
    private val chunkSamples = MicChunkCodec.SAMPLE_RATE * 15 / 1000

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** true solo con el permiso RECORD_AUDIO concedido por el usuario. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!hasPermission()) {
            running.set(false)
            return
        }
        val worker =
            Thread({
                captureLoop()
            }, "TwinpadMicCapture")
        worker.start()
        thread = worker
    }

    @Synchronized
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun captureLoop() {
        val minBufferSize =
            AudioRecord.getMinBufferSize(
                MicChunkCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).takeIf { it > 0 } ?: return
        val record =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    MicChunkCodec.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBufferSize, chunkSamples * BUFFER_MULTIPLE),
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "AudioRecord no disponible", e)
                return
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            return
        }
        val buffer = ShortArray(chunkSamples)
        try {
            record.startRecording()
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = record.read(buffer, 0, chunkSamples, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                if (read < chunkSamples) continue // fragmento incompleto: se descarta
                runCatching { onChunk(MicChunkCodec.encode(buffer, 0, chunkSamples)) }
                    .onFailure { android.util.Log.w(TAG, "mic_chunk inválido descartado", it) }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Captura de micrófono interrumpida", e)
        } finally {
            record.stop()
            record.release()
            running.set(false)
        }
    }

    private companion object {
        const val TAG = "PairingMic"

        /** Margen sobre el mínimo del SO para no subalimentar la captura. */
        const val BUFFER_MULTIPLE = 4
    }
}
