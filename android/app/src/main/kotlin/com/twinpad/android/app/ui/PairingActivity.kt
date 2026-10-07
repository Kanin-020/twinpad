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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.pairing.PairingViewModel

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
            val actions =
                PairingActions(
                    onScan = { viewModel.onEvent(PairingEvent.ScanRequested) },
                    onCancel = { viewModel.onEvent(PairingEvent.Cancelled) },
                    onRetry = { viewModel.onEvent(PairingEvent.Retry) },
                    onQrScanned = { raw -> viewModel.onEvent(PairingEvent.QrScanned(raw)) },
                )
            PairingScreen(state = state, actions = actions)
        }
    }
}
