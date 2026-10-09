package com.twinpad.android.app.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.pairing.PairingViewModel
import kotlinx.coroutines.launch

/**
 * Pantalla de Pairing (SPEC-002 §6): orquesta permisos, cámara y máquina
 * de estados. La UI observa `uiState` y ejecuta los efectos de cámara.
 */
class PairingActivity : ComponentActivity() {
    private val viewModel: PairingViewModel by viewModels()
    private var cameraController: PairingCameraController? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraController =
            PairingCameraController(this) { raw ->
                viewModel.onEvent(PairingEvent.QrScanned(raw))
            }
        setContent { PairingScreenRoot() }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppBackgrounded() // SPEC-002 §8.3
    }

    @Composable
    private fun PairingScreenRoot(viewModel: PairingViewModel = viewModel()) {
        val state by viewModel.uiState.collectAsState()
        var settings by remember { mutableStateOf(StreamSettings()) }

        LaunchedEffect(Unit) {
            viewModel.cameraEffects.collect { effect ->
                when (effect) {
                    is Effect.RequestCamera ->
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    is Effect.StartCamera -> cameraController?.start()
                    is Effect.StopCamera -> cameraController?.stop()
                    else -> Unit
                }
                // ShowError y demás mensajes viven en el estado U5/U4.
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PairingScreenScaffold(viewModel, state, settings, onSettingsChange = { settings = it })
        }
    }

    /** Compone la vista con sus callbacks (ENG P4: fuera del root, que ya
     * cumple el límite de longitud de detekt). */
    @Composable
    private fun PairingScreenScaffold(
        viewModel: PairingViewModel,
        state: com.twinpad.android.app.core.PairingUiState,
        settings: StreamSettings,
        onSettingsChange: (StreamSettings) -> Unit,
    ) {
        val actions =
            PairingActions(
                onScan = { viewModel.onEvent(PairingEvent.ScanRequested) },
                onCancel = { viewModel.onEvent(PairingEvent.Cancelled) },
                onRetry = { viewModel.onEvent(PairingEvent.Retry) },
                onQrScanned = { raw -> viewModel.onEvent(PairingEvent.QrScanned(raw)) },
                onGamepadInput = { report -> viewModel.onGamepadInput(report) },
            )
        val drawerState =
            androidx.compose.material3.rememberDrawerState(
                androidx.compose.material3.DrawerValue.Closed,
            )
        val drawerScope = androidx.compose.runtime.rememberCoroutineScope()
        PairingScreen(
            state = state,
            actions = actions,
            cameraController = cameraController,
            session =
                SessionUiBundle(
                    streamState = viewModel.streamState.collectAsState().value,
                    availableGames = viewModel.availableGames.collectAsState().value,
                    lastFrame = viewModel.lastFrame.collectAsState().value,
                    stats = viewModel.streamStats,
                    settings = settings,
                    actions = actions,
                ),
            callbacks =
                SessionSettingsCallbacks(
                    onSettingsChange = onSettingsChange,
                    onVideoQualityChange = { w, h, fps ->
                        viewModel.onVideoQualityChanged(w, h, fps)
                    },
                    drawerState = drawerState,
                    drawerOpen = { open ->
                        drawerScope.launch {
                            if (open) drawerState.open() else drawerState.close()
                        }
                    },
                ),
        )
    }
}
