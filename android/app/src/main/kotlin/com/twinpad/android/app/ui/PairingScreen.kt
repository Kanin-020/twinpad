package com.twinpad.android.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.twinpad.android.app.R
import com.twinpad.android.app.core.PairingError
import com.twinpad.android.app.core.PairingUiState

/**
 * Render de U1–U5 (SPEC-002 §6) con las cadenas exactas de §9 (R4).
 */
@Composable
fun PairingScreen(
    state: PairingUiState,
    actions: PairingActions,
    cameraController: PairingCameraController? = null,
    streamState: com.twinpad.android.app.stream.StreamUiState? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(24.dp))

        when (state) {
            PairingUiState.Idle -> {
                Button(onClick = actions.onScan) {
                    Text(stringResource(R.string.pairing_scan_button))
                }
            }

            PairingUiState.Scanning -> {
                CameraPreview(cameraController)
                Text(stringResource(R.string.pairing_scanning))
                CancelButton(actions.onCancel)
            }

            PairingUiState.Handshaking -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.pairing_handshaking))
                CancelButton(actions.onCancel)
            }

            is PairingUiState.Linked -> {
                Text(stringResource(R.string.pairing_linked))
                // SPEC-003 §5: vista de Segunda pantalla del stream activo.
                streamState?.let { s ->
                    SecondScreenView(s)
                }
            }

            is PairingUiState.Failure -> {
                Text(stringResource(state.error.resId()))
                RetryButton(actions.onRetry)
            }
        }
    }
}

/**
 * Vista de Segunda pantalla (SPEC-003 §5): presenta el Frame activo a
 * pantalla completa del área asignada, o el estado "sin segunda pantalla
 * activa" (REQ-004 AC-3) cuando no hay stream.
 */
@Composable
private fun SecondScreenView(streamState: com.twinpad.android.app.stream.StreamUiState) {
    val frame = streamState.lastFrame
    if (streamState.state == com.twinpad.android.app.stream.StreamState.STREAMING &&
        frame is android.graphics.Bitmap
    ) {
        androidx.compose.foundation.Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = stringResource(R.string.second_screen_active),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(320.dp),
        )
    } else {
        val message =
            if (streamState.state == com.twinpad.android.app.stream.StreamState.INTERRUPTED) {
                stringResource(R.string.second_screen_reconnecting)
            } else {
                stringResource(R.string.second_screen_inactive)
            }
        Text(message)
    }
}

@Composable
private fun CameraPreview(controller: PairingCameraController?) {
    AndroidView(
        factory = { context ->
            PreviewView(context).also { previewView ->
                controller?.bind(previewView, context as? LifecycleOwner)
            }
        },
        modifier = Modifier.fillMaxWidth().height(320.dp),
    )
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    Button(onClick = onCancel) { Text(stringResource(R.string.pairing_cancel_button)) }
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    Button(onClick = onRetry) { Text(stringResource(R.string.pairing_retry_button)) }
}

/** Mapeo exacto de SPEC-002 §9: cada error → su string resource. */
fun PairingError.resId(): Int =
    when (this) {
        PairingError.ServerUnavailable -> R.string.pairing_error_server_unavailable
        PairingError.QrExpired -> R.string.pairing_error_qr_expired
        PairingError.Rejected -> R.string.pairing_error_rejected
        PairingError.InvalidResponse -> R.string.pairing_error_invalid_response
        PairingError.QrInvalid -> R.string.pairing_error_invalid_qr
        PairingError.CameraDenied -> R.string.pairing_error_camera_denied
        PairingError.SessionEnded -> R.string.pairing_session_ended
    }
