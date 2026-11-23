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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** Store en memoria: evita Android Keystore (no disponible en la JVM de test). */
    private val memoryStore: (Application) -> com.twinpad.android.app.ui.PairingLinkStore = {
        object : com.twinpad.android.app.ui.PairingLinkStore(it) {
            private val links = mutableListOf<com.twinpad.android.app.ui.SavedLink>()

            override fun loadAll() = links.toList()

            override fun save(link: com.twinpad.android.app.ui.SavedLink) {
                links.removeAll { existing -> existing.sessionId == link.sessionId }
                links.add(link)
            }

            override fun remove(sessionId: String) {
                links.removeAll { existing -> existing.sessionId == sessionId }
            }
        }
    }

    /** nonce/token/session_id con longitudes exactas de SPEC-001 §4.1/§4.2. */
    private val nonceStr = "A".repeat(43)
    private val sessionId = "BQUFBQUFBQUFBQUFBQUFBQ"

    @Test
    fun usesInjectedPairingMachineForCameraFlow() {
        val machine = PairingStateMachine()
        val viewModel = PairingViewModel(app, PairingCollaborators(machine = machine, linkStore = memoryStore))

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
        val viewModel = PairingViewModel(app, PairingCollaborators(streamStats = stats, linkStore = memoryStore))

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
            val viewModel =
                PairingViewModel(app, PairingCollaborators(machine = machine, linkStore = memoryStore))

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

    /** TEST-0195 — verifies CR-094 §2.3: «Desconectar» cierra la sesión y
     * quita la vinculación local, así que al reabrir la app no se reanuda lo
     * que el usuario desconectó. Se usa TCP loopback real (test-strategy §10). */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun disconnectForgetsTheSavedLink() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val links = mutableListOf<com.twinpad.android.app.ui.SavedLink>()
            val store: (Application) -> com.twinpad.android.app.ui.PairingLinkStore = {
                object : com.twinpad.android.app.ui.PairingLinkStore(it) {
                    override fun loadAll() = links.toList()

                    override fun save(link: com.twinpad.android.app.ui.SavedLink) {
                        links.removeAll { existing -> existing.sessionId == link.sessionId }
                        links.add(link)
                    }

                    override fun remove(id: String) {
                        links.removeAll { existing -> existing.sessionId == id }
                    }
                }
            }
            val viewModel =
                PairingViewModel(
                    app,
                    PairingCollaborators(machine = PairingStateMachine(), linkStore = store),
                )

            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val serverThread =
                Thread {
                    runCatching {
                        server.accept().use { socket ->
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val writer = socket.getOutputStream()
                            reader.readLine() // pair_request
                            writer.write(encodeLine(HandshakeMessage.Challenge(nonceStr)).toByteArray())
                            writer.flush()
                            reader.readLine() // pair_proof
                            writer.write(encodeLine(HandshakeMessage.Ack(sessionId)).toByteArray())
                            writer.flush()
                            // Mantiene el socket abierto: la sesión sigue en U4 hasta
                            // que el usuario desconecta.
                            Thread.sleep(5_000)
                        }
                    }
                }
            serverThread.isDaemon = true
            serverThread.start()

            viewModel.onEvent(
                PairingEvent.QrScanned("twinpad://v1#s=127.0.0.1&p=${server.localPort}&t=$nonceStr"),
            )
            runBlocking {
                withTimeout(15_000) {
                    while (viewModel.uiState.value != PairingUiState.Linked(sessionId)) {
                        delay(20)
                    }
                }
            }
            assertEquals(1, links.size)

            viewModel.onDisconnectRequested()
            assertEquals(PairingUiState.Idle, viewModel.uiState.value)
            assertEquals("Desconectar debe olvidar la vinculación local", 0, links.size)
            server.close()
        } finally {
            Dispatchers.resetMain()
        }
    }


    /**
     * TEST-0199 (parcial) — tras quedar Linked, si el servidor envía audio_start + audio_chunk,
     * la app transiciona audioState a PLAYING (no se descartan por estar INACTIVE).
     * Regresión resume: si tras reanudar el server no reenvía audio_start, los chunks se descartan.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun linkedVmMovesToAudioPlayingOnAudioStartAndChunk() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        try {
            val machine = PairingStateMachine()
            val viewModel =
                PairingViewModel(app, PairingCollaborators(machine = machine, linkStore = memoryStore))
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            server.soTimeout = 10_000
            val uri = "twinpad://v1#s=127.0.0.1&p=${server.localPort}&t=$nonceStr"
            val done = CompletableFuture<Boolean>()
            val release = CompletableFuture<Boolean>()
            val thread = Thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val writer = socket.getOutputStream()
                        reader.readLine() // pair_request
                        writer.write((encodeLine(HandshakeMessage.Challenge(nonceStr))).toByteArray())
                        writer.flush()
                        reader.readLine() // pair_proof
                        writer.write((encodeLine(HandshakeMessage.Ack(sessionId))).toByteArray())
                        writer.flush()
                        Thread.sleep(200)
                        writer.write("{\"type\":\"audio_start\",\"rate\":48000,\"channels\":1,\"sample_fmt\":\"s16le\"}\n".toByteArray())
                        writer.flush()
                        Thread.sleep(50)
                        // 4 bytes PCM (2 samples s16le) en base64url: "AAAA" = 00 00 00 00
                        // 4 bytes PCM a cero en base64url ("AAAAAA"): PCM s16le par.
                        writer.write("{\"type\":\"audio_chunk\",\"seq\":1,\"ts\":0,\"data\":\"AAAAAA\"}\n".toByteArray())
                        writer.flush()
                        // Mantiene el socket abierto: un EOF reinicia el audio (A0).
                        release.get(15, TimeUnit.SECONDS)
                        done.complete(true)
                    }
                }.onFailure { done.completeExceptionally(it) }
            }
            thread.isDaemon = true
            thread.start()
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
            runBlocking { delay(300) }
            try {
                assertEquals(com.twinpad.android.app.audio.AudioState.PLAYING, viewModel.audioState.value)
            } finally {
                release.complete(true)
                done.get(10, TimeUnit.SECONDS)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }


    /**
     * TEST-0199 (resume) — CR-056 / SPEC-001 §8.2: tras un corte con la sesión
     * retenida, `pair_resume` restablece Linked y el audio que envía el servidor
     * después del `pair_ack` debe llegar a PLAYING. Se usa TCP loopback real.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resumedSessionPlaysAudioAfterPairResume() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val links = mutableListOf<com.twinpad.android.app.ui.SavedLink>()
            val store: (Application) -> com.twinpad.android.app.ui.PairingLinkStore = {
                object : com.twinpad.android.app.ui.PairingLinkStore(it) {
                    override fun loadAll() = links.toList()
                    override fun save(link: com.twinpad.android.app.ui.SavedLink) {
                        links.removeAll { existing -> existing.sessionId == link.sessionId }
                        links.add(link)
                    }
                    override fun remove(id: String) {
                        links.removeAll { existing -> existing.sessionId == id }
                    }
                }
            }
            val viewModel =
                PairingViewModel(app, PairingCollaborators(machine = PairingStateMachine(), linkStore = store))
            val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
            server.soTimeout = 20_000
            val resumeLineSeen = CompletableFuture<String>()
            val release = CompletableFuture<Boolean>()
            val serverThread =
                Thread {
                    runCatching {
                        // Conexión 1: handshake normal y corte del servidor.
                        server.accept().use { socket ->
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val writer = socket.getOutputStream()
                            reader.readLine() // pair_request
                            writer.write(encodeLine(HandshakeMessage.Challenge(nonceStr)).toByteArray())
                            writer.flush()
                            reader.readLine() // pair_proof
                            writer.write(encodeLine(HandshakeMessage.Ack(sessionId)).toByteArray())
                            writer.flush()
                            Thread.sleep(300)
                        }
                        // Conexión 2: pair_resume → pair_ack → audio del juego.
                        server.accept().use { socket ->
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            val writer = socket.getOutputStream()
                            resumeLineSeen.complete(reader.readLine())
                            writer.write(encodeLine(HandshakeMessage.Ack(sessionId)).toByteArray())
                            writer.flush()
                            writer.write("{\"type\":\"audio_start\",\"rate\":48000,\"channels\":1,\"sample_fmt\":\"s16le\"}\n".toByteArray())
                            writer.flush()
                            writer.write("{\"type\":\"audio_chunk\",\"seq\":1,\"ts\":0,\"data\":\"AAAAAA\"}\n".toByteArray())
                            writer.flush()
                            release.get(20, TimeUnit.SECONDS)
                        }
                    }.onFailure { resumeLineSeen.completeExceptionally(it) }
                }
            serverThread.isDaemon = true
            serverThread.start()

            viewModel.onEvent(PairingEvent.ScanRequested)
            viewModel.onCameraPermissionResult(granted = true)
            viewModel.onEvent(PairingEvent.QrScanned("twinpad://v1#s=127.0.0.1&p=${server.localPort}&t=$nonceStr"))
            try {
                val resumeLine = resumeLineSeen.get(30, TimeUnit.SECONDS)
                assertTrue("la reanudación debe enviar pair_resume: $resumeLine", resumeLine.contains("pair_resume"))
                runBlocking {
                    withTimeout(15_000) {
                        while (viewModel.audioState.value != com.twinpad.android.app.audio.AudioState.PLAYING) {
                            delay(20)
                        }
                    }
                }
                assertEquals(PairingUiState.Linked(sessionId), viewModel.uiState.value)
                assertEquals(com.twinpad.android.app.audio.AudioState.PLAYING, viewModel.audioState.value)
            } finally {
                release.complete(true)
                server.close()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun encodeLine(msg: HandshakeMessage): String = HandshakeCodec.encode(msg) + "\n"
}
