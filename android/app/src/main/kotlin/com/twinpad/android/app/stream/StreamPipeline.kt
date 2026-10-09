package com.twinpad.android.app.stream

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Resultado listo para la vista: frame decodificado + métricas del wire.
 */
data class DecodedFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val frameNumber: Long,
)

/**
 * Pipeline del stream de video (SPEC-003 R3: avanzar al frame más reciente).
 *
 * El lector de sesión encola líneas `video_frame` crudas aquí; un hilo IO
 * las decodifica. Si la decodificación va más lenta que la llegada (JPEG
 * 1080p en móviles medios), la cola capta el más reciente y descarta los
 * viejos SIN decodificarlos — la vista siempre muestra el frame más fresco
 * disponible y el lag no se acumula (§10: frames tardíos descartados).
 *
 * El audio NO pasa por aquí: viaja directo a [com.twinpad.android.app.audio]
 * desde el lector (R3 de ARCH §8: el audio no espera al video).
 */
class StreamPipeline(
    private val stats: StreamStats,
    private val onFrame: (DecodedFrame) -> Unit,
) {
    /** Capacidad 1: solo interesa el frame más reciente (R3). */
    private val pending =
        Channel<PendingFrame>(
            capacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Línea `video_frame` en crudo (sin decodificar aún). */
    private data class PendingFrame(
        val wireBytes: Int,
        val decode: () -> DecodedFrame?,
    )

    init {
        scope.launch {
            for (frame in pending) {
                val decoded = frame.decode()
                stats.onFrame(frame.wireBytes)
                if (decoded != null) {
                    onFrame(decoded)
                }
            }
        }
    }

    /**
     * Encola un frame para decodificar; si la cola está llena, el anterior
     * se descarta sin decodificar (DROP_OLDEST). `decode` es la thunk perezosa
     * (base64 + BitmapFactory) que corre en el hilo del pipeline.
     */
    fun submit(
        wireBytes: Int,
        decode: () -> DecodedFrame?,
    ) {
        pending.trySend(PendingFrame(wireBytes, decode))
    }

    /** Detiene el pipeline (fin de sesión). */
    fun close() {
        pending.close()
    }
}
