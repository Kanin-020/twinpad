package com.twinpad.android.app.pairing.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TcpPairingClientTest {
    /** TEST-0200 — tráfico de sesión encolado antes de `pair_ack` (p. ej.
     * `input_report`) se descarta sin bloquear el `pair_request` que va
     * detrás. Antes, la línea quedaba retenida y el handshake nunca salía.
     * TCP loopback real (test-strategy §10). */
    @Test
    fun preHandshakeSessionTrafficDoesNotBlockPairRequest() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server.soTimeout = 10_000
        val received = CompletableFuture<List<String>>()
        val harness =
            Thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        // input_report previo a pair_ack se descarta (E4): la primera
                        // línea que llega debe ser el pair_request.
                        received.complete(listOf(reader.readLine()))
                    }
                }.onFailure { received.completeExceptionally(it) }
            }
        harness.isDaemon = true
        harness.start()

        val client = TcpPairingClient()
        client.connect("127.0.0.1", server.localPort)
        client.send("{\"type\":\"input_report\",\"seq\":1}")
        client.send("{\"type\":\"pair_request\",\"v\":1}")

        assertEquals("{\"type\":\"pair_request\",\"v\":1}", received.get(10, TimeUnit.SECONDS).single())
        client.disconnect()
        server.close()
    }
}
