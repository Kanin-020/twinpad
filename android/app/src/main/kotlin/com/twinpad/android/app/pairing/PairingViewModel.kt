package com.twinpad.android.app.pairing

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twinpad.android.app.audio.AudioPlayback
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.network.TcpPairingClient
import com.twinpad.android.app.wire.GameListCodec
import com.twinpad.android.app.wire.StreamCodec
import com.twinpad.android.app.wire.StreamMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val NANOS_PER_MICROSECOND = 1_000L

/**
 * Único dueño del estado de la Pantalla de Pairing (DES-002 §4).
 * Alimenta la máquina con eventos y ejecuta sus [Effect]s: permisos,
 * conexión TCP y señales de cámara para la Activity.
 */
class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private data class PendingVideoLine(
        val line: String,
        val enqueuedAtNanos: Long,
        val frameNumber: Long?,
    )

    private val machine = PairingStateMachine()
    private val network = TcpPairingClient()
    private val streamMachine = com.twinpad.android.app.stream.StreamStateMachine()
    private val tileComposer = com.twinpad.android.app.stream.TileComposer()
    private val pendingVideoLines = Channel<PendingVideoLine>(Channel.UNLIMITED)
    private val audioMachine = com.twinpad.android.app.audio.AudioStateMachine()
    private val gamepad = com.twinpad.android.app.hid.GamepadStateMachine()
    private var selectionPending = false
    private var requestedGameId: String? = null
    private var automaticSelectionId: String? = null

    /** HUD del stream (fps/kbps); los mensajes se aplican en orden para
     * preservar la cadena base_fn de los patches (SPEC-003/CR-002). */
    val streamStats = com.twinpad.android.app.stream.StreamStats()
    private val _uiState = MutableStateFlow<PairingUiState>(machine.state)
    val uiState: StateFlow<PairingUiState> = _uiState

    /** Efectos que la Activity ejecuta (permiso, apertura/cierre de cámara). */
    private val _cameraEffects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    val cameraEffects: SharedFlow<Effect> = _cameraEffects

    /** Estado de la Segunda pantalla (SPEC-003 §5) para la vista de stream. */
    private val _streamState =
        MutableStateFlow(com.twinpad.android.app.stream.StreamUiState())
    val streamState: StateFlow<com.twinpad.android.app.stream.StreamUiState> = _streamState

    /** Juegos compatibles vivos (SPEC-006 §5): lista publicada por el server. */
    private val _availableGames =
        MutableStateFlow<List<com.twinpad.android.app.wire.GameInfo>>(emptyList())
    val availableGames: StateFlow<List<com.twinpad.android.app.wire.GameInfo>> = _availableGames

    /** Plugin confirmado para esta sesión (SPEC-006). */
    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId

    private val _selectionRequest =
        MutableSharedFlow<List<com.twinpad.android.app.wire.GameInfo>>(extraBufferCapacity = 1)
    val selectionRequest: SharedFlow<List<com.twinpad.android.app.wire.GameInfo>> = _selectionRequest

    /** Estado del stream de audio (SPEC-005 §5) para la vista. */
    private val _audioState =
        MutableStateFlow(com.twinpad.android.app.audio.AudioState.INACTIVE)
    val audioState: StateFlow<com.twinpad.android.app.audio.AudioState> = _audioState

    /** Bitmap listo para la vista (publicado por el pipeline de video). */
    private val _lastFrame =
        MutableStateFlow<com.twinpad.android.app.stream.DecodedFrame?>(null)
    val lastFrame: StateFlow<com.twinpad.android.app.stream.DecodedFrame?> = _lastFrame

    /** Adaptador de reproducción (AudioTrack). */
    private val playback = com.twinpad.android.app.audio.AudioPlayback()

    init {
        // Las líneas de sesión (video: 30 líneas/s de ~350 KB) se procesan
        // FUERA del hilo de UI: el parseo del JSON + el decode base64 en
        // Main congelaban toda la app (⚙ sin respuesta, video a ~1 fps
        // visible) mientras el HUD contaba los frames que sí llegaban.
        // Los StateFlow son thread-safe y la compose recomposición vive en
        // Main (ENG P10: el efecto pesado vive en el hilo correcto).
        viewModelScope.launch(Dispatchers.Default) {
            network.lines.collect { line ->
                if (_uiState.value is PairingUiState.Linked) {
                    onStreamLine(line) // SPEC-003: tráfico de sesión
                } else {
                    feed(PairingEvent.WireMessage(line))
                }
            }
        }
        // El compositor es un consumidor FIFO independiente: el decode JPEG
        // no detiene al lector de socket ni retrasa audio/controles (CR-002).
        viewModelScope.launch(Dispatchers.Default) {
            for (pending in pendingVideoLines) {
                streamPipelineSubmit(pending)
            }
        }
        // SPEC-003 §6: marcar V2 si el stream se queda sin frames; SPEC-005
        // §6: A2 si el audio supera el jitter buffer (500 ms).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(com.twinpad.android.app.wire.ProtocolConstants.STREAM_TICK_MS)
                val now = System.currentTimeMillis()
                val s = streamMachine.tick(now)
                if (s != _streamState.value.state) {
                    _streamState.value = _streamState.value.copy(state = s)
                }
                val a = audioMachine.tick(now)
                if (a != _audioState.value) {
                    _audioState.value = a
                }
            }
        }
    }

    /** SPEC-003: una línea de la fase de sesión (post-pair_ack).
     * Actualiza el estado del stream y publica el frame si aplica. */
    private fun onStreamLine(line: String?) {
        if (line == null) {
            // Corte de conexión con sesión activa: V0/A0 y sesión terminada
            // (SPEC-002 §9 la reporta; aquí solo limpiamos los streams).
            streamMachine.reset()
            tileComposer.reset()
            _streamState.value = com.twinpad.android.app.stream.StreamUiState()
            audioMachine.reset()
            playback.stop()
            _audioState.value = com.twinpad.android.app.audio.AudioState.INACTIVE
            _lastFrame.value = null
            streamStats.reset()
            _availableGames.value = emptyList()
            _selectedGameId.value = null
            selectionPending = false
            requestedGameId = null
            automaticSelectionId = null
            feed(PairingEvent.WireMessage(null))
            return
        }
        // Ruta rápida: separar por `type` SIN parsear todo el JSON (una
        // línea de video puede medir 500 KB; parsearla dos veces —audio y
        // video— con kotlinx.serialization costaba el doble de CPU).
        when (lineType(line)) {
            "games_available" -> {
                GameListCodec.decode(line)?.let(::handleGamesAvailable)
            }
            "game_selected" -> {
                GameListCodec.selectedGameId(line)?.let(::confirmGameSelection)
            }
            "audio_start", "audio_chunk", "audio_stop" -> {
                // R3 de ARCH §8: el audio no espera al video — se procesa
                // directo en el lector (decode barato: ≤ 16 KiB).
                if (audioMachine.onLine(line, System.currentTimeMillis())) {
                    _audioState.value = audioMachine.state
                    when (lineType(line)) {
                        "audio_start", "audio_stop" -> playback.stop()
                        "audio_chunk" -> playback.drain(audioMachine.drainPending())
                    }
                }
                streamStats.onAudio(line.length)
            }
            "video_frame", "video_patch" -> {
                val frameNumber = StreamCodec.frameNumber(line)
                if (frameNumber == null) {
                    streamStats.onFrameRejected()
                } else {
                    streamStats.onImageReceived(frameNumber, line.length)
                    pendingVideoLines.trySend(PendingVideoLine(line, System.nanoTime(), frameNumber))
                }
            }
            "stream_start", "stream_stop" ->
                pendingVideoLines.trySend(PendingVideoLine(line, System.nanoTime(), null))
            else -> Unit // mensaje desconocido: ignorado (§9)
        }
    }

    /** Encola el frame crudo al pipeline con su thunk de decodificación. */
    private fun streamPipelineSubmit(pending: PendingVideoLine) {
        val enteredAtNanos = System.nanoTime()
        val queueDelayNanos = enteredAtNanos - pending.enqueuedAtNanos
        if (pending.frameNumber != null) {
            streamStats.recordStage(
                com.twinpad.android.app.stream.StreamStats.VideoStage.LINE_TO_DECODE,
                queueDelayNanos,
            )
            com.twinpad.android.app.stream.VideoTrace.counter(
                "video.queue_delay_us",
                queueDelayNanos / NANOS_PER_MICROSECOND,
            )
        }
        val message =
            try {
                com.twinpad.android.app.stream.VideoTrace.section("video.codec") {
                    if (pending.frameNumber != null) {
                        com.twinpad.android.app.stream.VideoTrace.section("video.base64_and_json") {
                            StreamCodec.decode(pending.line)
                        }
                    } else {
                        StreamCodec.decode(pending.line)
                    }
                }
            } catch (e: com.twinpad.android.app.core.PairingException) {
                streamStats.onFrameRejected()
                return
            } finally {
                if (pending.frameNumber != null) {
                    val codecNanos = System.nanoTime() - enteredAtNanos
                    streamStats.recordStage(
                        com.twinpad.android.app.stream.StreamStats.VideoStage.CODEC,
                        codecNanos,
                    )
                    com.twinpad.android.app.stream.VideoTrace.counter(
                        "video.codec_us",
                        codecNanos / NANOS_PER_MICROSECOND,
                    )
                }
            }
        handleStreamMessage(message, pending.line)
    }

    private fun handleStreamMessage(
        message: StreamMessage,
        line: String,
    ) {
        when (message) {
            is StreamMessage.Start -> onStreamStart(message, line)
            is StreamMessage.Stop -> onStreamStop(line)
            is StreamMessage.Frame -> onKeyframe(message)
            is StreamMessage.Patch -> onPatch(message)
        }
    }

    private fun onStreamStart(
        message: StreamMessage.Start,
        line: String,
    ) {
        tileComposer.beginStream(message.width, message.height)
        streamMachine.onLine(line)
        _streamState.value =
            _streamState.value.copy(
                state = streamMachine.state,
                width = message.width,
                height = message.height,
            )
    }

    private fun onStreamStop(line: String) {
        tileComposer.reset()
        streamMachine.onLine(line)
        _lastFrame.value = null
        _streamState.value = _streamState.value.copy(state = streamMachine.state)
    }

    private fun onKeyframe(message: StreamMessage.Frame) {
        val startedAtNanos = System.nanoTime()
        val bitmap =
            com.twinpad.android.app.stream.VideoTrace.section("video.frame_decode") {
                tileComposer.applyKeyframe(message)
            }
        streamStats.recordStage(
            com.twinpad.android.app.stream.StreamStats.VideoStage.JPEG_DECODE,
            System.nanoTime() - startedAtNanos,
        )
        if (bitmap == null) {
            streamStats.onFrameRejected()
            return
        }
        acceptComposedFrame(message.frameNumber, message.width, message.height, bitmap)
    }

    private fun onPatch(message: StreamMessage.Patch) {
        val startedAtNanos = System.nanoTime()
        val bitmap =
            com.twinpad.android.app.stream.VideoTrace.section("video.tile_compose") {
                tileComposer.applyPatch(message)
            }
        streamStats.recordStage(
            com.twinpad.android.app.stream.StreamStats.VideoStage.TILE_COMPOSE,
            System.nanoTime() - startedAtNanos,
        )
        if (bitmap == null) {
            streamStats.onFrameRejected()
            return
        }
        acceptComposedFrame(message.frameNumber, message.width, message.height, bitmap)
    }

    private fun acceptComposedFrame(
        frameNumber: Long,
        width: Int,
        height: Int,
        bitmap: android.graphics.Bitmap,
    ) {
        streamMachine.acceptFrame(frameNumber, width, height)
        _lastFrame.value = com.twinpad.android.app.stream.DecodedFrame(bitmap, width, height, frameNumber)

        _streamState.value =
            _streamState.value.copy(
                state = com.twinpad.android.app.stream.StreamState.STREAMING,
                width = width,
                height = height,
            )
    }

    /** Solicita la fuente seleccionada; medios previos se limpian al confirmarse. */
    fun selectGame(gameId: String) {
        if (_availableGames.value.none { it.gameId == gameId }) return
        sendGameSelection(gameId)
    }

    private fun handleGamesAvailable(games: List<com.twinpad.android.app.wire.GameInfo>) {
        _availableGames.value = games
        val pendingId = requestedGameId
        if (pendingId != null && GameListCodec.retainedSelection(pendingId, games) == null) {
            selectionPending = false
            requestedGameId = null
        }
        val selected = _selectedGameId.value
        val retained = GameListCodec.retainedSelection(selected, games)
        if (selected != null && retained == null) {
            _selectedGameId.value = null
            clearMediaForSelectionChange()
        }

        when {
            games.isEmpty() -> automaticSelectionId = null
            games.size == 1 -> {
                automaticSelectionId = games.single().gameId
                if (selected != automaticSelectionId && requestedGameId != automaticSelectionId) {
                    sendGameSelection(automaticSelectionId!!)
                }
            }
            else -> {
                automaticSelectionId = null
                if (GameListCodec.needsExplicitSelection(games, requestedGameId)) {
                    _selectionRequest.tryEmit(games)
                }
            }
        }
    }

    private fun sendGameSelection(gameId: String) {
        requestedGameId = gameId
        selectionPending = true
        network.send(GameListCodec.selectionLine(gameId))
    }

    private fun confirmGameSelection(gameId: String) {
        if (!selectionPending || requestedGameId != gameId) return
        val previous = _selectedGameId.value
        if (previous != gameId) clearMediaForSelectionChange()
        _selectedGameId.value = gameId
        selectionPending = false
        requestedGameId = null
    }

    private fun clearMediaForSelectionChange() {
        tileComposer.reset()
        streamMachine.reset()
        _lastFrame.value = null
        _streamState.value = com.twinpad.android.app.stream.StreamUiState()
        audioMachine.reset()
        playback.stop()
        _audioState.value = com.twinpad.android.app.audio.AudioState.INACTIVE
    }

    /** Punto de entrada de la UI/capa Android hacia la máquina. */
    fun onEvent(event: PairingEvent) = feed(event)

    /** Ajuste de calidad del video en caliente (menú de la app): pide al
     * server nueva resolución/fps para las próximas sesiones (v1: el server
     * lo aplica al re-anunciar; la sesión vigente continúa). */
    fun onVideoQualityChanged(
        width: Int,
        height: Int,
        fps: Int,
    ) {
        val line =
            "{\"type\":\"video_config\",\"w\":$width,\"h\":$height,\"fps\":$fps}"
        network.send(line)
    }

    /** Ajuste de calidad JPEG en caliente (drawer, CR-001): 1–100. */
    fun onJpegQualityChanged(quality: Int) {
        val line = "{\"type\":\"video_config\",\"jpeg_quality\":$quality}"
        network.send(line)
    }

    /** SPEC-004 §7: un cambio de la superficie táctil genera un
     * `input_report` (canal: sesión LAN, ADR-007). Solo en Linked (H1);
     * el `seq` lo asigna la máquina del gamepad (R2). */
    fun onGamepadInput(report: com.twinpad.android.app.wire.InputReport) {
        val updated = gamepad.onElementChanged(report)
        if (updated != null) {
            network.send(com.twinpad.android.app.wire.InputCodec.encode(updated))
        }
    }

    /** Resultado del permiso de cámara confirmado por el SO (SPEC-002 §8.1). */
    fun onCameraPermissionResult(granted: Boolean) {
        val event = if (granted) PairingEvent.CameraGranted else PairingEvent.CameraDenied
        feed(event)
    }

    /** La app fue a segundo plano (SPEC-002 §8.3): aborto limpio. */
    fun onAppBackgrounded() = feed(PairingEvent.AppBackgrounded)

    private fun feed(event: PairingEvent) {
        val effects = machine.onEvent(event)
        _uiState.value = machine.state
        if (_uiState.value is PairingUiState.Linked) {
            // En U4 no hay timeouts de lectura: la sesión espera tráfico
            // de SPECs posteriores (DES-002 §8).
            network.disableReadTimeout()
            // SPEC-004 §6 H0→H1: canal operativo, reporte inicial en reposo
            // (solo en la transición; Linked persiste en los eventos posteriores).
            if (gamepad.state == com.twinpad.android.app.hid.GamepadState.OFFLINE) {
                gamepad.onChannelReady()?.let { network.send(com.twinpad.android.app.wire.InputCodec.encode(it)) }
            }
        } else if (gamepad.state != com.twinpad.android.app.hid.GamepadState.OFFLINE) {
            gamepad.onChannelDown() // §6 H1→H0: los controles dejan de enviar
        }
        for (effect in effects) {
            when (effect) {
                is Effect.RequestCamera ->
                    if (
                        ContextCompat.checkSelfPermission(
                            getApplication(),
                            android.Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        feed(PairingEvent.CameraGranted) // R2: solo pide si falta
                    } else {
                        _cameraEffects.tryEmit(effect) // Activity lanza el diálogo del SO
                    }

                is Effect.Connect -> network.connect(effect.addr, effect.port)
                is Effect.SendLine -> network.send(effect.line)
                is Effect.Disconnect -> network.disconnect()
                is Effect.StartCamera, is Effect.StopCamera, is Effect.ShowError ->
                    _cameraEffects.tryEmit(effect)
            }
        }
    }

    override fun onCleared() {
        pendingVideoLines.close()
        tileComposer.reset()
        playback.release()
        network.shutdown()
    }
}

/** Ruta rápida: extrae el valor de `"type"` sin parsear todo el JSON
 * (una línea de video puede medir 500 KB; parsearla dos veces —audio y
 * video— con kotlinx.serialization costaba el doble de CPU). */
private fun lineType(line: String): String? {
    val marker = "\"type\":\""
    val start = line.indexOf(marker)
    if (start < 0) return null
    val rest = line.substring(start + marker.length)
    return rest.substringBefore('"')
}
