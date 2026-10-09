package com.twinpad.android.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    session: SessionUiBundle? = null,
    callbacks: SessionSettingsCallbacks = SessionSettingsCallbacks(),
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
                // Vista de sesión: Segunda pantalla a pantalla completa con
                // HUD y menú encima; superficie táctil configurable.
                // El sidebar vive en un ModalNavigationDrawer Material3:
                // se abre con el botón Y arrastrando desde el borde izq.
                session?.let {
                    androidx.compose.material3.ModalNavigationDrawer(
                        drawerContent = {
                            ModalDrawerSheet {
                                SettingsPanel(
                                    settings = it.settings,
                                    onDismiss = { callbacks.drawerOpen(false) },
                                    onSettingsChange = callbacks.onSettingsChange,
                                    onVideoQualityChange = callbacks.onVideoQualityChange,
                                )
                            }
                        },
                        drawerState = callbacks.drawerState,
                    ) {
                        SessionView(it, callbacks)
                    }
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
 * Vista de sesión (estilo Wii U): Segunda pantalla a pantalla completa,
 * HUD de fps/kbps en la esquina y botón de ajustes. La superficie táctil
 * se muestra/oculta según el ajuste del usuario.
 */
@Composable
private fun SessionView(
    session: SessionUiBundle,
    callbacks: SessionSettingsCallbacks,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SessionVideoArea(session)
        val settings = session.settings
        if (settings.showFps && session.stats != null) {
            HudOverlay(
                fps = session.stats.fps,
                kbps = session.stats.kbps,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
        // Abre el drawer por botón (también se abre arrastrando el borde).
        TextButton(
            onClick = { callbacks.drawerOpen(true) },
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Text("⚙", color = Color.White)
        }
        if (settings.showControls) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = WINDOW_PADDING.dp)) {
                GamepadView(session.actions.onGamepadInput)
            }
        }
    }
}

/** Área de video: el frame más reciente a pantalla completa, o el estado. */
@Composable
private fun SessionVideoArea(session: SessionUiBundle) {
    val frame = session.lastFrame?.bitmap
    if (frame != null) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = stringResource(R.string.second_screen_active),
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.pairing_linked))
        if (session.availableGames.isNotEmpty()) {
            AvailableGamesView(session.availableGames)
        }
        Spacer(Modifier.height(8.dp))
        val message =
            when (session.streamState?.state) {
                com.twinpad.android.app.stream.StreamState.INTERRUPTED ->
                    stringResource(R.string.second_screen_reconnecting)
                else -> stringResource(R.string.second_screen_inactive)
            }
        Text(message)
    }
}

/** Conversión kbps → Mbps. */
private const val KBPS_PER_MBPS: Double = 1_000.0

/** Padding en esquinas de la vista de sesión (dp). */
private const val WINDOW_PADDING: Int = 8

/** HUD monocromo translúcido: "60.0 fps · 24.1 Mbps". */
@Composable
private fun HudOverlay(
    fps: Double,
    kbps: Double,
    modifier: Modifier = Modifier,
) {
    val mbps = kbps / KBPS_PER_MBPS
    Text(
        text = "%.0f fps · %.1f Mbps".format(fps, mbps),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Juegos compatibles detectados (SPEC-006 §5, REQ-006 AC-1): la app muestra
 * el/los juegos con SDK anunciados y vivos en el server.
 */
@Composable
private fun AvailableGamesView(games: List<com.twinpad.android.app.wire.GameInfo>) {
    val names = games.joinToString(", ") { it.gameName }
    val message =
        if (games.size == 1) {
            stringResource(R.string.game_compatible_one, names)
        } else {
            stringResource(R.string.game_compatible_many, names)
        }
    Text(message, style = MaterialTheme.typography.bodyMedium)
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
