package com.twinpad.android.app.pairing.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Timeout de conexión y de respuesta: 5 s (SPEC-002 §9, E1). */
private const val FIVE_SECONDS_MS: Int = 5_000

/**
 * Conexión TCP del lado app sobre WiFi LAN (ADR-002). Envía líneas NDJSON
 * y expone las recibidas como `Flow<String?>` (el `null` cierra: conexión
 * rechazada, timeout E1 o corte — SPEC-002 §10).
 *
 * Las líneas a enviar se encolan en [pendingWrites] y un único escritor las
 * drena en orden estricto una vez el socket está establecido (la máquina
 * emite `Connect` + `SendLine` seguidos; el envío no se pierde aunque el
 * handshake de TCP siga en curso — ENG P10: FIFO determinista).
 */
class TcpPairingClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lineChannel = Channel<String?>(capacity = Channel.UNLIMITED)
    private val pendingWrites = Channel<String>(capacity = Channel.UNLIMITED)
    private var readerJob: Job? = null
    private var socket: Socket? = null

    /** Líneas del servidor; `null` = conexión terminada. */
    val lines: Flow<String?> = lineChannel.receiveAsFlow()

    fun connect(
        addr: String,
        port: Int,
    ) {
        readerJob =
            scope.launch {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(addr, port), FIVE_SECONDS_MS)
                    s.soTimeout = FIVE_SECONDS_MS // sin respuesta en 5 s → E1
                    socket = s
                    startWriter(s)
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line =
                            try {
                                reader.readLine()
                            } catch (e: SocketTimeoutException) {
                                break // E1 (SPEC-002 §9)
                            }
                        if (line == null) break // EOF: el servidor cerró
                        lineChannel.trySend(line)
                    }
                } catch (e: java.io.IOException) {
                    // Conexión rechazada o cortada: el `finally` entrega `null`
                    // y la máquina lo interpreta como E1 (SPEC-002 §9).
                } finally {
                    closeQuietly()
                    lineChannel.trySend(null)
                }
            }
    }

    /** Inicia el único escritor: drena [pendingWrites] hacia `s` en FIFO. */
    private fun startWriter(s: Socket) {
        scope.launch {
            try {
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                for (line in pendingWrites) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            } catch (e: java.io.IOException) {
                // El socket cerró con escrituras en vuelo: señal de fin.
                closeQuietly()
                lineChannel.trySend(null)
            }
        }
    }

    /** Encola una línea NDJSON con `\n` final (SPEC-001 §4.2); el escritor
     * la transmite cuando la conexión esté lista. */
    fun send(line: String) {
        pendingWrites.trySend(line)
    }

    /** Desactiva el timeout de lectura al entrar en U4 (la sesión no envía
     * tráfico periódico hasta SPECs posteriores). */
    fun disableReadTimeout() {
        runCatching { socket?.soTimeout = 0 }
    }

    fun disconnect() {
        readerJob?.cancel()
        closeQuietly()
    }

    fun shutdown() {
        scope.cancel()
        closeQuietly()
    }

    /** Cierra el socket y descarta escrituras pendientes: sin conexión ya
     * no tienen destino (un intento abortado no deja basura para el siguiente). */
    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        while (true) {
            if (pendingWrites.tryReceive().getOrNull() == null) break
        }
    }
}
