package com.twinpad.android.app.session

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.stream.StreamState
import com.twinpad.android.app.stream.StreamUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * CR-121: mantiene al día la notificación del servicio de sesión. Con sesión
 * viva y stream entregando frames (V1/V2 de SPEC-003) dice «Transmitiendo…»;
 * con la sesión conectada pero sin frames, el texto de sesión conectada.
 *
 * Vive fuera de `PairingActivity` para no engordar la Activity (detekt
 * `LargeClass`); no arranca ni detiene el servicio, eso lo hace la Activity.
 */
object SessionNotificationSync {
    fun bind(
        activity: ComponentActivity,
        uiState: StateFlow<PairingUiState>,
        streamState: StateFlow<StreamUiState>,
    ) {
        activity.lifecycleScope.launch {
            streamState.collectLatest { state ->
                // Sin sesión viva el servicio ya está detenido (y su
                // notificación retirada): no hay nada que refrescar.
                if (uiState.value !is PairingUiState.Linked) return@collectLatest
                SessionForegroundService.update(activity, streaming = state.state == StreamState.STREAMING)
            }
        }
    }
}
