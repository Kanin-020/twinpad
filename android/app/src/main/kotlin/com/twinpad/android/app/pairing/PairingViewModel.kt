package com.twinpad.android.app.pairing

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.network.TcpPairingClient
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
    private val gamepad = com.twinpad.android.app.hid.GamepadStateMachine()

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
        // SPEC-003 §6: marcar V2 si el stream se queda sin frames.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(com.twinpad.android.app.wire.ProtocolConstants.STREAM_TICK_MS)
                val s = streamMachine.tick(System.currentTimeMillis())
                if (s != _streamState.value.state) {
                    _streamState.value = _streamState.value.copy(state = s)
                }
            }
        }
    }

    /** SPEC-003: una línea de la fase de sesión (post-pair_ack).
     * Actualiza el estado del stream y publica el frame si aplica. */
    private fun onStreamLine(line: String?) {
        if (line == null) {
            // Corte de conexión con sesión activa: V0 y sesión terminada
            // (SPEC-002 §9 la reporta; aquí solo limpiamos el stream).
            streamMachine.reset()
            _streamState.value = com.twinpad.android.app.stream.StreamUiState()
            _availableGames.value = emptyList()
            feed(PairingEvent.WireMessage(null))
            return
        }
        // SPEC-006 §5: la lista de juegos compatibles viaja por la misma
        // sesión; se consume aquí y no entra a la máquina del stream.
        val games = com.twinpad.android.app.wire.GameListCodec.decode(line)
        if (games != null) {
            _availableGames.value = games
            return
        }
        val frame = streamMachine.onLine(line)
        val newState = streamMachine
        _streamState.value =
            com.twinpad.android.app.stream.StreamUiState(
                state = newState.state,
                lastFrame = frame ?: _streamState.value.lastFrame,
                width = newState.width,
                height = newState.height,
            )
    }

    /** Punto de entrada de la UI/capa Android hacia la máquina. */
    fun onEvent(event: PairingEvent) = feed(event)

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
        network.shutdown()
    }
}
