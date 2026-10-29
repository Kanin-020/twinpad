package com.twinpad.android.app.pairing

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.twinpad.android.app.audio.AudioPlayback
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.network.TcpPairingClient
import com.twinpad.android.app.ui.AndroidSettingsStore
import com.twinpad.android.app.wire.CameraRequestCodec
import com.twinpad.android.app.wire.GameListCodec
import com.twinpad.android.app.wire.MicRequestCodec
import com.twinpad.android.app.wire.RumbleCodec
import com.twinpad.android.app.wire.RumbleMessage
import com.twinpad.android.app.wire.StreamCodec
import com.twinpad.android.app.wire.StreamMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val GYRO_LOG_REPORT_INTERVAL = 10
private const val GYRO_LOG_TAG = "TwinpadGyro"

/**
 * Colaboradores del [PairingViewModel]. Los valores por defecto son las
 * instancias de producción; los tests pueden sustituir cualquiera.
 */
data class PairingCollaborators(
    val machine: PairingStateMachine = PairingStateMachine(),
    val network: TcpPairingClient = TcpPairingClient(),
    val streamMachine: com.twinpad.android.app.stream.StreamStateMachine =
        com.twinpad.android.app.stream.StreamStateMachine(),
    val audioMachine: com.twinpad.android.app.audio.AudioStateMachine =
        com.twinpad.android.app.audio.AudioStateMachine(),
    val gamepad: com.twinpad.android.app.hid.GamepadStateMachine =
        com.twinpad.android.app.hid.GamepadStateMachine(),
    val playback: AudioPlayback = AudioPlayback(),
    val streamStats: com.twinpad.android.app.stream.StreamStats =
        com.twinpad.android.app.stream.StreamStats(),
)

/**
 * Único dueño del estado de la Pantalla de Pairing (DES-002 §4).
 * Alimenta la máquina con eventos y ejecuta sus [Effect]s: permisos,
 * conexión TCP y señales de cámara para la Activity.
 */
class PairingViewModel(
    app: Application,
    collaborators: PairingCollaborators = PairingCollaborators(),
) : AndroidViewModel(app) {
    private val machine = collaborators.machine
    private val network = collaborators.network
    private val streamMachine = collaborators.streamMachine
    private val audioMachine = collaborators.audioMachine
    private val gamepad = collaborators.gamepad
    private val playback = collaborators.playback
    val streamStats = collaborators.streamStats
    private val rumblePlayer = RumblePlayer(app)
    private val selection = PairingSelectionState()

    /** HUD del stream (fps/kbps); los mensajes se aplican en orden para
     * preservar la cadena base_fn de los patches (SPEC-003/CR-002). */
    private val _uiState = MutableStateFlow<PairingUiState>(machine.state)
    val uiState: StateFlow<PairingUiState> = _uiState

    /** Efectos que la Activity ejecuta (permiso, apertura/cierre de cámara). */
    private val _cameraEffects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    val cameraEffects: SharedFlow<Effect> = _cameraEffects

    /** Estado de la Segunda pantalla (SPEC-003 §5) para la vista de stream. */
    private val _streamState =
        MutableStateFlow(com.twinpad.android.app.stream.StreamUiState())
    val streamState: StateFlow<com.twinpad.android.app.stream.StreamUiState> = _streamState
    private val mediaSession =
        PairingMediaSession(
            PairingMediaDependencies(
                viewModelScope,
                machine,
                network,
                streamMachine,
                streamStats,
            ),
            { _streamState.value },
        ) { state ->
            _streamState.value = state
        }

    /** Juegos compatibles vivos (SPEC-006 §5): lista publicada por el server. */
    private val _availableGames =
        MutableStateFlow<List<com.twinpad.android.app.wire.GameInfo>>(emptyList())
    val availableGames: StateFlow<List<com.twinpad.android.app.wire.GameInfo>> = _availableGames

    /** Plugin confirmado para esta sesión (SPEC-006). */
    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId

    /** CR-038: demanda vigente del video de cámara emitida por Cemu
     * (`camera_request`); gobernada por el uso real del juego (HLE
     * `camera`). Se aplica solo con el juego "cemu" seleccionado. */
    private val _cameraRequested = MutableStateFlow<Boolean>(false)
    val cameraRequested: StateFlow<Boolean> = _cameraRequested

    /** CR-035: demanda vigente del micrófono emitida por Cemu
     * (`mic_request`); gobernada por MICOpen/MICClose del juego en el HLE
     * `mic`. El teléfono solo graba con demanda activa (bajo demanda). */
    private val _micRequested = MutableStateFlow<Boolean>(false)
    val micRequested: StateFlow<Boolean> = _micRequested

    private val _selectionRequest =
        MutableSharedFlow<List<com.twinpad.android.app.wire.GameInfo>>(extraBufferCapacity = 1)
    val selectionRequest: SharedFlow<List<com.twinpad.android.app.wire.GameInfo>> = _selectionRequest

    /** Estado del stream de audio (SPEC-005 §5) para la vista. */
    private val _audioState =
        MutableStateFlow(com.twinpad.android.app.audio.AudioState.INACTIVE)
    val audioState: StateFlow<com.twinpad.android.app.audio.AudioState> = _audioState

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
        // SPEC-003 §6: marcar V2 si el stream se queda sin frames; SPEC-005
        // §6: A2 si el audio supera el jitter buffer (500 ms).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(com.twinpad.android.app.wire.ProtocolConstants.STREAM_TICK_MS)
                val now = System.currentTimeMillis()
                val streamStatus = streamMachine.tick(now)
                if (streamStatus != _streamState.value.state) {
                    _streamState.value = _streamState.value.copy(state = streamStatus)
                }
                val audioStatus = audioMachine.tick(now)
                if (audioStatus != _audioState.value) {
                    _audioState.value = audioStatus
                }
            }
        }
    }

    /** SPEC-003: una línea de la fase de sesión (post-pair_ack).
     * Actualiza el estado del stream y publica el frame si aplica. */
    private fun onStreamLine(line: String?) {
        if (line == null) {
            onSessionDisconnected()
            return
        }
        // La ruta rápida separa `type` sin parsear payloads grandes de medios.
        when (lineType(line)) {
            "games_available" -> GameListCodec.decode(line)?.let(::handleGamesAvailable)
            "game_selected" -> GameListCodec.selectedGameId(line)?.let(::confirmGameSelection)
            "audio_start", "audio_chunk", "audio_stop" -> handleAudioLine(line)
            "stream_start", "stream_stop" -> handleStreamControlLine(line)
            "video_frame", "video_patch" -> streamStats.onFrameRejected()
            "rumble" -> RumbleCodec.decode(line)?.let(rumblePlayer::play)
            "camera_request" -> CameraRequestCodec.decode(line)?.let { _cameraRequested.value = it.on }
            "mic_request" -> MicRequestCodec.decode(line)?.let { _micRequested.value = it.on }
            else -> Unit
        }
    }

    private fun onSessionDisconnected() {
        clearStreamSession()
        _availableGames.value = emptyList()
        _selectedGameId.value = null
        selection.clear()
        feed(PairingEvent.WireMessage(null))
    }

    private fun clearStreamSession() {
        mediaSession.clearForSessionEnd()
        streamMachine.reset()
        _streamState.value = com.twinpad.android.app.stream.StreamUiState()
        audioMachine.reset()
        playback.stop()
        _audioState.value = com.twinpad.android.app.audio.AudioState.INACTIVE
        streamStats.reset()
        rumblePlayer.play(RumbleMessage(ByteArray(0)))
    }

    private fun handleAudioLine(line: String) {
        if (audioMachine.onLine(line, System.currentTimeMillis())) {
            _audioState.value = audioMachine.state
            when (lineType(line)) {
                "audio_start", "audio_stop" -> playback.stop()
                "audio_chunk" -> playback.drain(audioMachine.drainPending())
            }
        }
        if (lineType(line) == "audio_chunk") {
            streamStats.onAudioChunkReceived(line.length)
        } else {
            streamStats.onAudio(line.length)
        }
    }

    private fun handleStreamControlLine(line: String) {
        when (lineType(line)) {
            "stream_start" -> {
                val message = runCatching { StreamCodec.decode(line) as? StreamMessage.Start }.getOrNull()
                if (message == null) streamStats.onFrameRejected() else mediaSession.onStreamStart(message, line)
            }
            "stream_stop" -> {
                val message = runCatching { StreamCodec.decode(line) as? StreamMessage.Stop }.getOrNull()
                if (message == null) streamStats.onFrameRejected() else mediaSession.onStreamStop(line)
            }
        }
    }

    fun attachMediaSurface(surface: android.view.Surface?) = mediaSession.attachSurface(surface)

    /** Solicita la fuente seleccionada; medios previos se limpian al confirmarse. */
    fun selectGame(gameId: String) {
        if (_availableGames.value.none { it.gameId == gameId }) return
        sendGameSelection(gameId)
    }

    private fun handleGamesAvailable(games: List<com.twinpad.android.app.wire.GameInfo>) {
        _availableGames.value = games
        val pendingId = selection.requestedGameId
        if (pendingId != null && GameListCodec.retainedSelection(pendingId, games) == null) {
            selection.pending = false
            selection.requestedGameId = null
        }
        val selected = _selectedGameId.value
        val retained = GameListCodec.retainedSelection(selected, games)
        if (selected != null && retained == null) {
            _selectedGameId.value = null
            clearMediaForSelectionChange()
        }

        when {
            games.isEmpty() -> selection.automaticGameId = null
            games.size == 1 -> {
                selection.automaticGameId = games.single().gameId
                if (selected != selection.automaticGameId && selection.requestedGameId != selection.automaticGameId) {
                    sendGameSelection(selection.automaticGameId!!)
                }
            }
            else -> {
                selection.automaticGameId = null
                if (GameListCodec.needsExplicitSelection(games, selection.requestedGameId)) {
                    _selectionRequest.tryEmit(games)
                }
            }
        }
    }

    private fun sendGameSelection(gameId: String) {
        selection.requestedGameId = gameId
        selection.pending = true
        network.send(GameListCodec.selectionLine(gameId))
    }

    private fun confirmGameSelection(gameId: String) {
        if (!selection.pending || selection.requestedGameId != gameId) return
        val previous = _selectedGameId.value
        if (previous != gameId) clearMediaForSelectionChange()
        _selectedGameId.value = gameId
        selection.pending = false
        selection.requestedGameId = null
    }

    private fun clearMediaForSelectionChange() {
        mediaSession.clearForSessionEnd()
        streamMachine.reset()
        _streamState.value = com.twinpad.android.app.stream.StreamUiState()
        resetAudioSession()
        // CR-038/035: sin sesión no hay demanda vigente que gobierne la
        // cámara ni el micrófono del teléfono.
        _cameraRequested.value = false
        _micRequested.value = false
    }

    private fun resetAudioSession() {
        audioMachine.reset()
        playback.stop()
        _audioState.value = com.twinpad.android.app.audio.AudioState.INACTIVE
    }

    /** Punto de entrada de la UI/capa Android hacia la máquina. */
    fun onEvent(event: PairingEvent) = feed(event)

    /** Líneas de sesión media enviadas por el teléfono hacia el Servidor
     * (CR-034: `camera_frame`; CR-035: `mic_chunk` solo con demanda vigente). */
    fun sendSessionLine(line: String) = network.send(line)

    /** Ajuste de calidad del video en caliente (menú de la app): pide al
     * server nueva resolución/bitrate para las próximas sesiones (CR-029:
     * sin fps — el server emite según el ritmo del juego; v1: el server
     * lo aplica al re-anunciar; la sesión vigente continúa). */
    fun onStreamSettingsChanged(
        settings: com.twinpad.android.app.ui.AndroidSettings =
            AndroidSettingsStore(getApplication()).load(),
    ) {
        AndroidSettingsStore(getApplication()).save(settings)
        network.send(settings.videoConfigLine())
    }

    /** SPEC-004 §7: un cambio de la superficie táctil genera un
     * `input_report` (canal: sesión LAN, ADR-007). Solo en Linked (H1);
     * el `seq` lo asigna la máquina del gamepad (R2). */
    fun setAudioVolume(volume: Float) = playback.setVolume(volume)

    fun onGamepadInput(report: com.twinpad.android.app.wire.InputReport) {
        val updated = gamepad.onElementChanged(report)
        if (updated != null) {
            updated.motion?.takeIf { updated.seq % GYRO_LOG_REPORT_INTERVAL == 0 }?.let { motion ->
                Log.i(
                    GYRO_LOG_TAG,
                    "stage=android seq=${updated.seq} timestamp_us=${motion.timestampUs} " +
                        "accel=${motion.accel.joinToString(",")} gyro=${motion.gyro.joinToString(",")}",
                )
            }
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
        mediaSession.close()
        playback.release()
        network.shutdown()
    }

    companion object {
        /** Factory de producción: el ViewModel usa sus colaboradores por defecto. */
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PairingViewModel::class.java)) {
                        "Clase de ViewModel no soportada: ${modelClass.name}"
                    }
                    return modelClass.cast(PairingViewModel(application, PairingCollaborators()))
                }
            }
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
