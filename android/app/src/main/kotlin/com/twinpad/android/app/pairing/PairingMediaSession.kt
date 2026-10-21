package com.twinpad.android.app.pairing

import android.os.SystemClock
import android.view.Surface
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.stream.H264MediaClient
import com.twinpad.android.app.stream.H264SurfaceDecoder
import com.twinpad.android.app.stream.StreamState
import com.twinpad.android.app.stream.StreamStats
import com.twinpad.android.app.stream.StreamUiState
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

/** Owns the authenticated H.264 media socket and its Surface decoder. */
internal class PairingMediaSession(
    private val dependencies: PairingMediaDependencies,
    private val currentStreamState: () -> StreamUiState,
    private val setStreamState: (StreamUiState) -> Unit,
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

    private val accessUnits = Channel<PendingAccessUnit>(capacity = 2)
    private val generation = AtomicLong(0)

    @Volatile
    private var client: H264MediaClient? = null

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

    fun onStreamStart(
        message: StreamMessage.Start,
        line: String,
    ) {
        reconnectJob?.cancel()
        reconnectJob = null
        closeChannel()
        streamStartLine = line
        dimensions = message.width to message.height
        mediaPort = message.mediaPort
        streamStats.resetVideoStream()
        streamMachine.onLine(line)
        val current = currentStreamState()
        setStreamState(
            current.copy(state = StreamState.INTERRUPTED, width = message.width, height = message.height),
        )
        connectIfReady()
        scheduleReconnect()
    }

    fun onStreamStop(line: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        closeChannel()
        streamStartLine = null
        dimensions = null
        streamMachine.onLine(line)
        val current = currentStreamState()
        setStreamState(current.copy(state = streamMachine.state, width = 0, height = 0))
    }

    fun attachSurface(output: Surface?) {
        if (surface === output) return
        closeChannel()
        surface = output
        if (output != null) {
            connectIfReady()
            scheduleReconnect()
        }
    }

    fun clearForSessionEnd() {
        reconnectJob?.cancel()
        reconnectJob = null
        closeChannel()
        dimensions = null
        streamStartLine = null
    }

    fun close() {
        accessUnits.close()
        clearForSessionEnd()
        surface = null
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
    ): H264SurfaceDecoder? =
        try {
            H264SurfaceDecoder(connection.output) { frameNumber ->
                onFramePresented(connectionGeneration, frameNumber, connection.frameSize)
            }.also { it.configure(connection.frameSize.first, connection.frameSize.second) }
        } catch (_: Exception) {
            null
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
        if (connectionGeneration != generation.get() || !isNewFrame(frameNumber)) return
        if (!streamMachine.acceptFrame(frameNumber, frameSize.first, frameSize.second)) return
        lastPresentedFrameNumber = frameNumber
        val current = currentStreamState()
        setStreamState(
            current.copy(state = StreamState.STREAMING, width = frameSize.first, height = frameSize.second),
        )
        streamStats.onFramePresented(frameNumber)
    }

    private fun isNewFrame(frameNumber: Long): Boolean = lastPresentedFrameNumber?.let { frameNumber > it } ?: true

    private fun createClient(
        connection: ConnectionParameters,
        connectionGeneration: Long,
        outputDecoder: H264SurfaceDecoder,
    ): H264MediaClient =
        H264MediaClient(
            host = connection.host,
            sessionId = connection.credentials.sessionId,
            sessionKey = connection.credentials.sessionKey,
            port = mediaPort,
            onAccessUnit = { unit ->
                onAccessUnit(unit, connection.output, connectionGeneration, outputDecoder)
            },
            onClosed = { onClientClosed(connectionGeneration) },
        )

    private fun onAccessUnit(
        unit: H264MediaClient.EncodedAccessUnit,
        output: Surface,
        connectionGeneration: Long,
        outputDecoder: H264SurfaceDecoder,
    ) {
        if (connectionGeneration != generation.get() || surface !== output) return
        streamStats.onMediaAccessUnitReceived(unit.frameNumber, unit.bytes.size)
        val discontinuity = isFrameDiscontinuous(unit.frameNumber)
        lastReceivedFrameNumber = unit.frameNumber
        if (discontinuity) {
            clearQueuedAccessUnits()
            outputDecoder.resetForDiscontinuity()
        }
        if (!discontinuity || unit.idr) {
            enqueueAccessUnit(unit, output, connectionGeneration, outputDecoder)
        }
    }

    private fun isFrameDiscontinuous(frameNumber: Long): Boolean {
        val lastReceived = lastReceivedFrameNumber ?: return false
        return frameNumber != lastReceived + 1L
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
        val current = currentStreamState()
        setStreamState(current.copy(state = StreamState.INTERRUPTED))
        client = null
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

private fun mediaCredentials(
    machine: PairingStateMachine,
    linked: PairingUiState.Linked,
): MediaCredentials? {
    val sessionId = runCatching { HandshakeCrypto.decodeB64(linked.sessionId) }.getOrNull() ?: return null
    val sessionKey = machine.sessionKeyOrNull()?.copyOf() ?: return null
    return MediaCredentials(sessionId, sessionKey)
}
