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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Único dueño del estado de la Pantalla de Pairing (DES-002 §4).
 * Alimenta la máquina con eventos y ejecuta sus [Effect]s: permisos,
 * conexión TCP y señales de cámara para la Activity.
 */
class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private val machine = PairingStateMachine()
    private val network = TcpPairingClient()
    private val streamMachine = com.twinpad.android.app.stream.StreamStateMachine()
    private val audioMachine = com.twinpad.android.app.audio.AudioStateMachine()
    private val gamepad = com.twinpad.android.app.hid.GamepadStateMachine()

    /** HUD del stream (fps/kbps) y pipeline de decodificación en hilo IO
     * con descarte de frames viejos (SPEC-003 R3: el render avanza al
     * más reciente; el lag no se acumula). */
    val streamStats = com.twinpad.android.app.stream.StreamStats()
    private val pipeline =
        com.twinpad.android.app.stream.StreamPipeline(streamStats) { decoded ->
            streamMachine.acceptFrame(decoded.frameNumber, decoded.width, decoded.height)
            _lastFrame.value = decoded
            _streamState.value =
                _streamState.value.copy(
                    state = com.twinpad.android.app.stream.StreamState.STREAMING,
                    width = decoded.width,
                    height = decoded.height,
                )
        }

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

    /** Estado del stream de audio (SPEC-005 §5) para la vista. */
    private val _audioState =
        MutableStateFlow(com.twinpad.android.app.audio.AudioState.INACTIVE)
    val audioState: StateFlow<com.twinpad.android.app.audio.AudioState> = _audioState

    /** Bitmap listo para la vista (publicado por el pipeline de video). */
    private val _lastFrame =
        MutableStateFlow<com.twinpad.android.app.stream.DecodedFrame?>(null)
    val lastFrame: StateFlow<com.twinpad.android.app.stream.DecodedFrame?> = _lastFrame

    /** Adaptador de reproducción (AudioTrack); inyectable para tests. */
    private val playback = com.twinpad.android.app.audio.AudioPlayback()

    init {
        viewModelScope.launch {
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
            _streamState.value = com.twinpad.android.app.stream.StreamUiState()
            audioMachine.reset()
            _audioState.value = com.twinpad.android.app.audio.AudioState.INACTIVE
            _lastFrame.value = null
            streamStats.reset()
            _availableGames.value = emptyList()
            feed(PairingEvent.WireMessage(null))
            return
        }
        // Ruta rápida: separar por `type` SIN parsear todo el JSON (una
        // línea de video puede medir 500 KB; parsearla dos veces —audio y
        // video— con kotlinx.serialization costaba el doble de CPU).
        when (lineType(line)) {
            "games_available" -> {
                com.twinpad.android.app.wire.GameListCodec.decode(line)?.let {
                    _availableGames.value = it
                }
            }
            "audio_start", "audio_chunk", "audio_stop" -> {
                // R3 de ARCH §8: el audio no espera al video — se procesa
                // directo en el lector (decode barato: ≤ 16 KiB).
                if (audioMachine.onLine(line, System.currentTimeMillis())) {
                    _audioState.value = audioMachine.state
                    playback.drain(audioMachine.drainPending())
                }
                streamStats.onAudio(line.length)
            }
            "video_frame", "stream_start", "stream_stop" -> {
                // SPEC-003: el frame viaja al pipeline (decode en hilo IO,
                // descarte de viejos); los de control afectan al estado.
                if (line.startsWith("{\"type\":\"video_frame\"")) {
                    streamPipelineSubmit(line)
                } else {
                    streamMachine.onLine(line)
                    _streamState.value = _streamState.value.copy(state = streamMachine.state)
                }
            }
            else -> Unit // mensaje desconocido: ignorado (§9)
        }
    }

    /** Encola el frame crudo al pipeline con su thunk de decodificación. */
    private fun streamPipelineSubmit(line: String) {
        val frame =
            try {
                StreamCodec.decode(line) as? StreamMessage.Frame
            } catch (e: com.twinpad.android.app.core.PairingException) {
                null
            } ?: return
        pipeline.submit(line.length) {
            val bitmap =
                com.twinpad.android.app.stream.FrameRenderer.decodeBase64AndRender(
                    frame.data,
                    frame.width,
                    frame.height,
                    frame.fmt,
                )
            bitmap?.let {
                com.twinpad.android.app.stream.DecodedFrame(it, frame.width, frame.height, frame.frameNumber)
            }
        }
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
            "{{\"type\":\"video_config\",\"w\":$width,\"h\":$height,\"fps\":$fps}}"
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

    /** Resultado del diálogo de permiso de cámara del SO (SPEC-002 §8.1). */
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
                    if (hasCameraPermission()) {
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

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
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
