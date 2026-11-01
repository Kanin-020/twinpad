package com.twinpad.android.app.pairing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.stream.StreamStats
import com.twinpad.android.app.wire.HandshakeCodec
import com.twinpad.android.app.wire.HandshakeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * TEST-091 — verifies that [PairingViewModel] delega en los colaboradores que
 * recibe por [PairingCollaborators] (SPEC-002 §8.1: permiso de cámara antes de
 * escanear). Se usan instancias reales de las máquinas puras; la red no se conecta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** nonce/token/session_id con longitudes exactas de SPEC-001 §4.1/§4.2. */
    private val nonceStr = "A".repeat(43)
    private val sessionId = "BQUFBQUFBQUFBQUFBQUFBQ"

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

    /**
     * TEST-0123 — CR-055 HB2 (SPEC-001 §4.3): en U4 el colector del
     * [PairingViewModel] debe alimentar a la máquina con las líneas `ping`
     * (que responden `pong` con eco de `t`). Regresión: la ruta de medios de
     * U4 tragaba el `ping`, el Servidor vencía HB4 a los 15 s y la sesión se
     * cortaba durante el stream. Se usa TCP loopback real (test-strategy §10:
     * el protocolo no se mockea).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun linkedVmAnswersHeartbeatPingOverRealLoopback() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        try {
            val machine = PairingStateMachine()
            val viewModel = PairingViewModel(app, PairingCollaborators(machine = machine))

            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            server.soTimeout = 10_000
            val uri = "twinpad://v1#s=127.0.0.1&p=${server.localPort}&t=$nonceStr"

            val pongReceived = CompletableFuture<String>()
            val harness =
                Thread {
                    runCatching {
                        server.accept().use { socket ->
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val writer = socket.getOutputStream()
                            // Paso 5: pair_request; paso 7: pair_challenge.
                            reader.readLine()
                            writer.write((encodeLine(HandshakeMessage.Challenge(nonceStr))).toByteArray())
                            writer.flush()
                            // Paso 8: pair_proof (la máquina lo computa; no se verifica aquí).
                            reader.readLine()
                            // Paso 10: pair_ack → la app queda en U4/Vinculado.
                            writer.write((encodeLine(HandshakeMessage.Ack(sessionId))).toByteArray())
                            writer.flush()
                            // HB1: ping t=0; el eco debe volver como `pong`.
                            writer.write((encodeLine(HandshakeMessage.Ping(0))).toByteArray())
                            writer.flush()
                            // Puede cruzar el input_report inicial de reposo
                            // (SPEC-004 §6): se ignoran las líneas que no
                            // sean el eco del heartbeat.
                            var pong: String? = null
                            repeat(8) {
                                if (pong == null) {
                                    val line = reader.readLine()
                                    if (line != null && line.startsWith("{\"type\":\"pong\"")) {
                                        pong = line
                                    }
                                }
                            }
                            pongReceived.complete(pong ?: "sin eco del heartbeat")
                        }
                    }.onFailure { pongReceived.completeExceptionally(it) }
                }
            harness.isDaemon = true
            harness.start()

            viewModel.onEvent(PairingEvent.ScanRequested)
            viewModel.onCameraPermissionResult(granted = true)
            viewModel.onEvent(PairingEvent.QrScanned(uri))

            runBlocking {
                withTimeout(15_000) {
                    while (viewModel.uiState.value != PairingUiState.Linked(sessionId)) {
                        delay(20)
                    }
                }
            }
            assertEquals(
                "{\"type\":\"pong\",\"t\":0}",
                pongReceived.get(10, TimeUnit.SECONDS),
            )
            // El corte interactivo no se simula: al cerrar el socket de la
            // prueba, el cliente recibe EOF y la máquina pasa a "Sesión
            // terminada" por sí sola (SPEC-002 §6).
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun encodeLine(msg: HandshakeMessage): String = HandshakeCodec.encode(msg) + "\n"
}
