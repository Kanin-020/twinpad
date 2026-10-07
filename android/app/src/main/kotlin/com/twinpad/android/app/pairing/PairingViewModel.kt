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

    private val _uiState = MutableStateFlow<PairingUiState>(machine.state)
    val uiState: StateFlow<PairingUiState> = _uiState

    /** Efectos que la Activity ejecuta (permiso, apertura/cierre de cámara). */
    private val _cameraEffects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    val cameraEffects: SharedFlow<Effect> = _cameraEffects

    init {
        viewModelScope.launch {
            network.lines.collect { line -> feed(PairingEvent.WireMessage(line)) }
        }
    }

    /** Punto de entrada de la UI/capa Android hacia la máquina. */
    fun onEvent(event: PairingEvent) = feed(event)

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
