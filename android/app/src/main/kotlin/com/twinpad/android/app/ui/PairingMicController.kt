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
    /**
     * Lint no infiere que la construcción de `AudioRecord` está protegida por
     * `hasPermission()` (verificado en `start()` tras `checkSelfPermission` y
     * en `PairingActivity.onCreate` con runtime request de RECORD_AUDIO).
     * Se silencia el warning aquí mediante anotación explícita (CR-047 §6 del
     * checklist de gates: `ktlint`/`detekt` y tests verdes sin warnings de
     * permiso en build).
     */
    @Suppress("MissingPermission")
    private fun makeAudioRecord(): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            MicChunkCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBufferBytes(), chunkSamples * BUFFER_MULTIPLE),
        )

    /**
     * Mínimo del SO en bytes para la captura. `getMinBufferSize` devuelve un
     * código de error si la combinación no está soportada; en ese caso se usa
     * un respaldo acotado (no `Int.MAX_VALUE`, que impediría construir el
     * `AudioRecord` y dejaría el micrófono sin capturar).
     */
    private fun minimumBufferBytes(): Int =
        AudioRecord.getMinBufferSize(
            MicChunkCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).takeIf { it > 0 } ?: chunkSamples * BUFFER_FALLBACK_MULTIPLE

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

    @Suppress("MissingPermission")
    private fun captureLoop() {
        val record =
            try {
                makeAudioRecord()
            } catch (e: Exception) {
                // Sin esto el flag quedaba activado y un `start()` posterior no
                // volvía a intentar la captura (el micrófono moría en silencio).
                android.util.Log.w(TAG, "AudioRecord no disponible", e)
                running.set(false)
                return
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            return
        }
        val buffer = ShortArray(chunkSamples)
        var filled = 0
        try {
            record.startRecording()
            while (running.get() && !Thread.currentThread().isInterrupted) {
                // AudioRecord puede devolver menos muestras de las pedidas: se
                // acumulan hasta completar el bloque en vez de descartar el
                // tramo parcial, que dejaba huecos de hasta 15 ms y se oía
                // como micrófono cortado.
                val read = record.read(buffer, filled, chunkSamples - filled, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                filled += read
                if (filled < chunkSamples) continue
                filled = 0
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

        /** Respaldo acotado cuando el SO no da un mínimo válido. */
        const val BUFFER_FALLBACK_MULTIPLE = 40
    }
}
