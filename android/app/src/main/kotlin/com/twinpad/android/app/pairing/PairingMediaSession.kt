package com.twinpad.android.app.pairing

import android.os.SystemClock
import android.view.Surface
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.stream.H264MediaClient
import com.twinpad.android.app.stream.H264SurfaceDecoder
import com.twinpad.android.app.stream.MediaClient
import com.twinpad.android.app.stream.StreamState
import com.twinpad.android.app.stream.StreamStats
import com.twinpad.android.app.stream.StreamUiState
import com.twinpad.android.app.stream.UdpPreferredEvent
import com.twinpad.android.app.stream.UdpPreferredMediaClient
import com.twinpad.android.app.wire.HandshakeCrypto
import com.twinpad.android.app.wire.MediaCrypto
import com.twinpad.android.app.wire.StreamMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val MEDIA_RECOVERY_WINDOW_MS = 10_000L
private const val MEDIA_RECONNECT_INITIAL_DELAY_MS = 250L
private const val MEDIA_RECONNECT_MAX_DELAY_MS = 2_000L

/**
 * Capacidad del canal entre lector media y feeder del decodificador. Con 2
 * unidades, una ráfaga de llegadas (WiFi) saturaba el canal y el fallback
 * `trySend` dispara clear+reset del decodificador — la imagen se congela
 * hasta la IDR periódica (≈1 s), el microcorte descrito por el owner.
 * 32 AUs absorben ráfagas largas sin pagar retardación: el feeder drena el
 * FIFO y solo entrega AU estrictamente consecutivas al decodificador.
 */
private const val ACCESS_UNIT_CHANNEL_CAPACITY = 32

/** Ciclo de vida del media de la sesión: reemplaza cliente y decodificador
 * por generación de conexión y reintentos con backoff mientras la ventana
 * de recuperación esté activa (SPEC-003 §6; CR-029, CR-062). */
internal class PairingMediaSession(
    private val dependencies: PairingMediaDependencies,
    private val currentStreamState: () -> StreamUiState,
    private val setStreamState: (StreamUiState) -> Unit,
    /** CR-075: transporte media vigente de la conexión (true=UDP, false=TCP). */
    private val setMediaTransport: (Boolean) -> Unit,
) {
    private val scope = dependencies.scope
    private val machine = dependencies.machine
    private val network = dependencies.network
    private val streamMachine = dependencies.streamMachine
    private val streamStats = dependencies.streamStats

    private data class PendingAccessUnit(
        val unit: H264MediaClient.EncodedAccessUnit,
        val generation: Long,
        val surface: Surface,
    )

    private val accessUnits = Channel<PendingAccessUnit>(capacity = ACCESS_UNIT_CHANNEL_CAPACITY)
    private val generation = AtomicLong(0)

    @Volatile
    private var client: MediaClient? = null

    @Volatile
    private var decoder: H264SurfaceDecoder? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var dimensions: Pair<Int, Int>? = null

    @Volatile
    private var mediaPort = MediaCrypto.PORT
    private var lastPresentedFrameNumber: Long? = null
    private var lastReceivedFrameNumber: Long? = null
    private var reconnectJob: Job? = null
    private var streamStartLine: String? = null

    init {
        scope.launch(Dispatchers.Default) {
            for (pending in accessUnits) {
                val activeDecoder = decoderFor(pending) ?: continue
                val started = System.nanoTime()
                activeDecoder.queue(pending.unit)
                streamStats.recordStage(
                    StreamStats.VideoStage.MEDIA_CODEC,
                    System.nanoTime() - started,
                )
            }
        }
    }

    /** Registra el `stream_start` del server (SPEC-003 §4): corta el canal
     * vigente, fija dimensiones/puerto y agenda la reconexión. */
    fun onStreamStart(
        message: StreamMessage.Start,
        line: String,
    ) {
        cancelReconnectJob()
        closeChannel()
        streamStartLine = line
        dimensions = message.width to message.height
        mediaPort = message.mediaPort
        // CR-029: el fps del `stream_start` es metadata (unlimited=0) y no
        // configura el decoder; el pacing ya no existe.
        streamStats.resetVideoStream()
        streamMachine.onLine(line)
        val current = currentStreamState()
        setStreamState(
            current.copy(state = StreamState.INTERRUPTED, width = message.width, height = message.height),
        )
        connectIfReady()
        scheduleReconnect()
    }

    /** Registra el `stream_stop` del server (SPEC-003 §4): termina el
     * canal vigente y refleja el estado del stream machine. */
    fun onStreamStop(line: String) {
        cancelReconnectJob()
        closeChannel()
        streamStartLine = null
        dimensions = null
        streamMachine.onLine(line)
        val current = currentStreamState()
        setStreamState(current.copy(state = streamMachine.state, width = 0, height = 0))
    }

    /** Conecta o desconecta la Surface de salida (ciclo de la Activity):
     * sin surface vigente la canalización se drena y no hay reconexión. */
    fun attachSurface(output: Surface?) {
        if (surface === output) return
        closeChannel()
        surface = output
        if (output != null) {
            connectIfReady()
            scheduleReconnect()
        }
    }

    /** Normaliza el estado al cierre de la sesión de Pairing: corta el
     * canal y la agenda de reconexión sin tocar la surface. */
    fun clearForSessionEnd() {
        cancelReconnectJob()
        closeChannel()
        dimensions = null
        streamStartLine = null
    }

    /** Cierre definitivo: corta reconexión, canal y surface vigente. */
    fun close() {
        accessUnits.close()
        clearForSessionEnd()
        surface = null
    }

    /** Cancela la agenda de reconexión vigente (patrón repetido del ciclo
     * de vida, unificado aquí). */
    private fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun decoderFor(pending: PendingAccessUnit): H264SurfaceDecoder? =
        if (
            pending.generation == generation.get() && surface === pending.surface &&
            client != null
        ) {
            decoder
        } else {
            null
        }

    private fun connectIfReady() {
        val connection = connectionParameters() ?: return
        val connectionGeneration = generation.incrementAndGet()
        lastPresentedFrameNumber = null
        lastReceivedFrameNumber = null
        val newDecoder = createDecoder(connection, connectionGeneration) ?: return
        decoder = newDecoder
        createClient(connection, connectionGeneration, newDecoder).also { newClient ->
            client = newClient
            newClient.start()
        }
    }

    private fun createDecoder(
        connection: ConnectionParameters,
        connectionGeneration: Long,
    ): H264SurfaceDecoder? {
        val newDecoder =
            try {
                H264SurfaceDecoder(connection.output) { frameNumber ->
                    onFramePresented(connectionGeneration, frameNumber, connection.frameSize)
                }
            } catch (_: Exception) {
                return null
            }
        return try {
            // CR-029: sin frameRate del wire; el decoder usa el metadata real.
            newDecoder.configure(connection.frameSize.first, connection.frameSize.second)
            newDecoder
        } catch (_: Exception) {
            newDecoder.close()
            null
        }
    }

    private data class ConnectionParameters(
        val output: Surface,
        val frameSize: Pair<Int, Int>,
        val credentials: MediaCredentials,
        val host: String,
    )

    private fun connectionParameters(): ConnectionParameters? {
        val output = surface
        val frameSize = dimensions
        val linked = machine.state as? PairingUiState.Linked
        if (output == null || frameSize == null || linked == null) return null
        val credentials = mediaCredentials(machine, linked)
        val host = network.remoteHost()
        return if (credentials != null && host != null) {
            ConnectionParameters(output, frameSize, credentials, host)
        } else {
            null
        }
    }

    private fun onFramePresented(
        connectionGeneration: Long,
        frameNumber: Long,
        frameSize: Pair<Int, Int>,
    ) {
        if (connectionGeneration != generation.get() ||
            !isNewFrame(lastPresentedFrameNumber, frameNumber)
        ) {
            return
        }
        if (!streamMachine.acceptFrame(frameNumber, frameSize.first, frameSize.second)) return
        lastPresentedFrameNumber = frameNumber
        val current = currentStreamState()
        setStreamState(
            current.copy(state = StreamState.STREAMING, width = frameSize.first, height = frameSize.second),
        )
        streamStats.onFramePresented(frameNumber)
    }

    /** CR-062 (SPEC-003 §4.4): cliente preferente UDP con fallback TCP;
     * la AU 1ª autenticada define el canal vigente de la conexión. */
    private fun createClient(
        connection: ConnectionParameters,
        connectionGeneration: Long,
        outputDecoder: H264SurfaceDecoder,
    ): MediaClient =
        UdpPreferredMediaClient(
            host = connection.host,
            sessionId = connection.credentials.sessionId,
            sessionKey = connection.credentials.sessionKey,
            port = mediaPort,
            onAccessUnit = { unit ->
                onAccessUnit(unit, connection.output, connectionGeneration, outputDecoder)
            },
            onEvent = { event ->
                when (event) {
                    is UdpPreferredEvent.TransportActive -> setMediaTransport(event.isUdp)
                    UdpPreferredEvent.Closed -> onClientClosed(connectionGeneration)
                }
            },
        )

    private fun onAccessUnit(
        unit: H264MediaClient.EncodedAccessUnit,
        output: Surface,
        connectionGeneration: Long,
        outputDecoder: H264SurfaceDecoder,
    ) {
        if (connectionGeneration != generation.get() || surface !== output) return
        streamStats.onMediaAccessUnitReceived(unit.frameNumber, unit.bytes.size)
        val discontinuity = isFrameDiscontinuous(lastReceivedFrameNumber, unit.frameNumber)
        lastReceivedFrameNumber = unit.frameNumber
        if (discontinuity) {
            // Hueco §14: un único punto de re-sync, el gate del decoder
            // (waitingForIdr, abierto solo por IDR). SIN limpiar la cola de
            // sesión ni flush del codec: las AU previas al hueco son
            // válidas (§5) y se presentan; las posteriores se descartan
            // hasta el IDR. El doble clear+reset anterior reiniciaba el
            // codec dos veces por hueco y congelaba ~333 ms extra.
            if (!unit.idr) return
        }
        enqueueAccessUnit(unit, output, connectionGeneration, outputDecoder)
    }

    private fun clearQueuedAccessUnits() {
        while (accessUnits.tryReceive().isSuccess) Unit
    }

    private fun enqueueAccessUnit(
        unit: H264MediaClient.EncodedAccessUnit,
        output: Surface,
        connectionGeneration: Long,
        outputDecoder: H264SurfaceDecoder,
    ) {
        val pending = PendingAccessUnit(unit, connectionGeneration, output)
        if (accessUnits.trySend(pending).isFailure) {
            clearQueuedAccessUnits()
            outputDecoder.resetForDiscontinuity()
        }
    }

    private fun onClientClosed(connectionGeneration: Long) {
        if (connectionGeneration != generation.get()) return
        generation.incrementAndGet()
        val current = currentStreamState()
        setStreamState(current.copy(state = StreamState.INTERRUPTED))
        client = null
        clearQueuedAccessUnits()
        decoder?.close()
        decoder = null
        lastPresentedFrameNumber = null
        lastReceivedFrameNumber = null
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val expectedStart = streamStartLine ?: return
        val expectedSurface = surface ?: return
        reconnectJob =
            scope.launch(Dispatchers.IO) {
                val deadline = SystemClock.elapsedRealtime() + MEDIA_RECOVERY_WINDOW_MS
                var delayMs = MEDIA_RECONNECT_INITIAL_DELAY_MS
                while (isRecoveryActive(expectedStart, expectedSurface, deadline)) {
                    if (currentStreamState().state == StreamState.STREAMING) {
                        reconnectJob = null
                        return@launch
                    }
                    if (client == null) connectIfReady()
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(MEDIA_RECONNECT_MAX_DELAY_MS)
                }
                finishRecovery(expectedStart, expectedSurface)
                reconnectJob = null
            }
    }

    private fun isRecoveryActive(
        startLine: String,
        output: Surface,
        deadline: Long,
    ): Boolean =
        streamStartLine == startLine && surface === output &&
            machine.state is PairingUiState.Linked && SystemClock.elapsedRealtime() < deadline

    private fun finishRecovery(
        startLine: String,
        output: Surface,
    ) {
        if (
            streamStartLine != startLine || surface !== output ||
            currentStreamState().state == StreamState.STREAMING
        ) {
            return
        }
        closeChannel()
        streamMachine.reset()
        val current = currentStreamState()
        setStreamState(current.copy(state = StreamState.INACTIVE))
        dimensions = null
        streamStartLine = null
    }

    private fun closeChannel() {
        generation.incrementAndGet()
        clearQueuedAccessUnits()
        client?.close()
        client = null
        decoder?.close()
        decoder = null
        lastPresentedFrameNumber = null
        lastReceivedFrameNumber = null
    }
}

private data class MediaCredentials(val sessionId: ByteArray, val sessionKey: ByteArray)

/** `true` solo si el frame presentado es estrictamente posterior al último
 * aceptado (§10: idempotencia); el primero de la conexión siempre es nuevo. */
private fun isNewFrame(
    lastPresented: Long?,
    frameNumber: Long,
): Boolean = lastPresented?.let { frameNumber > it } ?: true

/** Discontinuidad de número de frame recibido (§14: hueco → re-sync decoder). */
private fun isFrameDiscontinuous(
    lastReceived: Long?,
    frameNumber: Long,
): Boolean = lastReceived != null && frameNumber != lastReceived + 1L

private fun mediaCredentials(
    machine: PairingStateMachine,
    linked: PairingUiState.Linked,
): MediaCredentials? {
    val sessionId = runCatching { HandshakeCrypto.decodeB64(linked.sessionId) }.getOrNull() ?: return null
    val sessionKey = machine.sessionKeyOrNull()?.copyOf() ?: return null
    return MediaCredentials(sessionId, sessionKey)
}
