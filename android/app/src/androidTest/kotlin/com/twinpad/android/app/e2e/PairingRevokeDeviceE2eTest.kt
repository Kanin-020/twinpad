package com.twinpad.android.app.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.PairingViewModel
import com.twinpad.android.app.ui.PairingActivity
import com.twinpad.android.app.ui.PairingLinkStore
import com.twinpad.android.app.wire.HandshakeCodec
import com.twinpad.android.app.wire.HandshakeCrypto
import com.twinpad.android.app.wire.HandshakeMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TEST-0219 — verifies CR-102/SPEC-001 §8.3 en un dispositivo real:
 * la app empareja contra el Servidor PC de la LAN, «Desconectar» —en el mismo
 * tick del `pair_ack`— envía `pair_revoke`, borra la vinculación local y un
 * `pair_resume` posterior con la misma `session_id`/`session_key` recibe E3.
 * También cubre que el stop de `SessionForegroundService` no adelante al
 * `startForeground` pendiente (evita `ForegroundServiceDidNotStartInTime`).
 *
 * Requiere un servidor vivo y recibe el URI vigente por argumento:
 * `-e qr_uri "twinpad://v1#s=…&p=8420&t=…"`.
 */
@RunWith(AndroidJUnit4::class)
class PairingRevokeDeviceE2eTest {
    @Test(timeout = 90_000)
    fun disconnect_revokes_the_server_registration_and_resume_is_e3() {
        val uri =
            requireNotNull(InstrumentationRegistry.getArguments().getString("qr_uri")) {
                "falta el argumento -e qr_uri con el URI vigente del servidor"
            }
        val (host, port) = parseEndpoint(uri)
        Log.i(TAG, "lanzando PairingActivity")
        val scenario = ActivityScenario.launch(PairingActivity::class.java)
        Log.i(TAG, "PairingActivity lanzada")
        try {
            lateinit var viewModel: PairingViewModel
            scenario.onActivity { activity ->
                viewModel = field(activity, "viewModel")
                viewModel.onCameraPermissionResult(granted = true)
                viewModel.onEvent(PairingEvent.QrScanned(uri))
            }
            Log.i(TAG, "QR enviado al ViewModel")
            awaitCondition("Pairing autenticado") {
                viewModel.uiState.value is PairingUiState.Linked ||
                    viewModel.uiState.value is PairingUiState.Failure
            }
            assertTrue(
                "Pairing falló: ${viewModel.uiState.value}",
                viewModel.uiState.value is PairingUiState.Linked,
            )
            val linked = viewModel.uiState.value as PairingUiState.Linked
            Log.i(TAG, "pair_ack: session_id=${linked.sessionId}")
            val machine: PairingStateMachine = field(viewModel, "machine")
            val sessionKey =
                requireNotNull(machine.sessionKeyOrNull()) {
                    "la sesión vinculada no expone session_key"
                }
            val sessionId = HandshakeCrypto.decodeB64(linked.sessionId)

            Log.i(TAG, "pulsando Desconectar")
            // La UI de sesión anima continuamente: onActivity/waitForIdleSync
            // nunca vería el looper ocioso, así que se despacha sin esperar idle.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.onDisconnectRequested()
            }
            Log.i(TAG, "Desconectar despachado; estado=${viewModel.uiState.value}")
            awaitCondition("vuelta a Idle tras Desconectar") {
                viewModel.uiState.value == PairingUiState.Idle
            }
            // El revoke es fire-and-forget (SPEC-002 R24): se le da margen al
            // socket nuevo antes de comprobar que la ligadura ya no reanuda.
            SystemClock.sleep(REVOKE_SETTLE_MS)

            val nonce = HandshakeCrypto.randomNonce()
            val proof = HandshakeCrypto.computeResumeProof(sessionKey, sessionId, nonce)
            val resume =
                HandshakeCodec.encode(
                    HandshakeMessage.Resume(
                        linked.sessionId,
                        HandshakeCrypto.encodeB64(nonce),
                        HandshakeCrypto.encodeB64(proof),
                    ),
                )
            Log.i(TAG, "enviando pair_resume tras revoke")
            val response = sendLine(host, port, resume)
            val message = HandshakeCodec.decode(response)
            Log.i(TAG, "respuesta al resume: $message")
            assertTrue(
                "el resume posterior debe recibir E3, llegó: $message",
                message is HandshakeMessage.Error && message.code == "E3",
            )

            val linkStore: PairingLinkStore = field(viewModel, "linkStore")
            assertTrue(
                "Desconectar debe olvidar la vinculación local",
                linkStore.loadAll().isEmpty(),
            )
        } finally {
            scenario.close()
        }
    }

    private fun sendLine(
        host: String,
        port: Int,
        line: String,
    ): String =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).apply {
                write(line)
                write("\n")
                flush()
            }
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                ?: error("el servidor cerró sin responder al pair_resume")
        }

    private fun parseEndpoint(uri: String): Pair<String, Int> {
        val query = uri.substringAfter('#')
        val host =
            requireNotNull(Regex("(?:^|&)s=([^&]+)").find(query)?.groupValues?.get(1)) {
                "el URI no trae s="
            }
        val port =
            requireNotNull(Regex("(?:^|&)p=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull()) {
                "el URI no trae p="
            }
        return host to port
    }

    private fun awaitCondition(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + CONDITION_TIMEOUT_MS
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue("Timeout esperando $description", predicate())
    }

    private inline fun <reified T> field(
        target: Any,
        name: String,
    ): T {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(target) as T
    }

    private companion object {
        const val TAG = "TwinpadE2E"
        const val CONDITION_TIMEOUT_MS = 35_000L
        const val POLL_INTERVAL_MS = 50L
        const val SOCKET_TIMEOUT_MS = 5_000
        const val REVOKE_SETTLE_MS = 4_000L
    }
}
