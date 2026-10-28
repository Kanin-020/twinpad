package com.twinpad.android.app.pairing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.stream.StreamStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-091 — verifies that [PairingViewModel] delega en los colaboradores que
 * recibe por [PairingCollaborators] (SPEC-002 §8.1: permiso de cámara antes de
 * escanear). Se usan instancias reales de las máquinas puras; la red no se conecta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun usesInjectedPairingMachineForCameraFlow() {
        val machine = PairingStateMachine()
        val viewModel = PairingViewModel(app, PairingCollaborators(machine = machine))

        // Sin permiso concedido la máquina pide la cámara y se queda en Idle.
        viewModel.onEvent(PairingEvent.ScanRequested)
        assertEquals(PairingUiState.Idle, viewModel.uiState.value)

        // El SO confirma el permiso: la máquina inyectada pasa a Scanning.
        viewModel.onCameraPermissionResult(granted = true)
        assertEquals(PairingUiState.Scanning, machine.state)
        assertEquals(PairingUiState.Scanning, viewModel.uiState.value)
    }

    @Test
    fun exposesInjectedStreamStats() {
        val stats = StreamStats()
        val viewModel = PairingViewModel(app, PairingCollaborators(streamStats = stats))

        assertSame(stats, viewModel.streamStats)
    }
}
