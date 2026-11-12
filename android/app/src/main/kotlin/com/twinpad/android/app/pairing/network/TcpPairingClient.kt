package com.twinpad.android.app.pairing.network

import com.twinpad.android.app.wire.ProtocolConstants
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
import java.util.concurrent.atomic.AtomicBoolean

/** Timeout de conexión y de respuesta: 5 s (SPEC-002 §9, E1). */
private const val FIVE_SECONDS_MS: Int = 5_000

/**
 * Conexión TCP del lado app sobre WiFi LAN (ADR-002). Envía líneas NDJSON
 * y expone las recibidas como `Flow<String?>` (el `null` cierra: conexión
 * rechazada, timeout E1, corte o silencio del heartbeat — SPEC-002 §10 y
 * SPEC-001 §4.3 HB5, CR-055).
 *
 * Las líneas a enviar se encolan en [pendingWrites] y un único escritor las
 * drena en orden estricto una vez el socket está establecido (la máquina
 * emite `Connect` + `SendLine` seguidos; el envío no se pierde aunque el
 * handshake de TCP siga en curso — ENG P10: FIFO determinista).
 */
class TcpPairingClient(
    /** CR-055 (HB5): silencio máximo en sesión antes de declarar el corte.
     * Inyectable para tests (producción: 15 s, el Servidor hace ping cada 5 s). */
    private val sessionSilenceTimeoutMs: Int = ProtocolConstants.SESSION_HEARTBEAT_DEADLINE_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lineChannel = Channel<String?>(capacity = Channel.UNLIMITED)
    private val pendingWrites = Channel<String>(capacity = Channel.UNLIMITED)

    private companion object {
        /** Tipos que el servidor acepta antes de `pair_ack` (handshake). Cualquier
         * otro tipo, como `input_report`, `video_config` o `game_select`, se
         * rechaza con E4 y no se escribe. */
        val HANDSHAKE_TYPES = listOf("pair_request", "pair_proof", "pair_resume")
    }

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var socket: Socket? = null

    @Volatile private var connectedHost: String? = null

    fun remoteHost(): String? = connectedHost

    /** Desactiva el timeout PRÓXIMO al ver `pair_ack` en líneas: corrido por
     * el MISMO hilo lector que aplica `soTimeout`, así no hay carrera contra
     * la UI. El corte a ~5 s de la U4 venía de dejarlo a cargo de la UI:
     * llegaba DESPUÉS de que el lector ya estaba bloqueado en `readLine`
     * con el timeout armado, y el `SocketTimeoutException` mataba la sesión. */
    private val sessionMode = AtomicBoolean(false)

    /** Socket cuyo `pair_ack` ya llegó. Atado al socket (no un booleano global)
     * para que el lector de un intento viejo no habilite el tráfico del nuevo. */
    @Volatile
    private var handshakeSocket: Socket? = null

    /** Líneas del servidor; `null` = conexión terminada. */
    val lines: Flow<String?> = lineChannel.receiveAsFlow()

    fun connect(
        addr: String,
        port: Int,
    ) {
        readerJob =
            scope.launch {
                // CR-056: la vida del socket abarca TODO el bloque (el closing
                // `finally` lo referencia por intento, fuera del try).
                val pairingSocket = Socket()
                try {
                    pairingSocket.connect(InetSocketAddress(addr, port), FIVE_SECONDS_MS)
                    pairingSocket.soTimeout = FIVE_SECONDS_MS // sin respuesta en 5 s → E1
                    // CR-049: Nagle absent — sin esto, los input_report (y las
                    // líneas mic_chunk/camera_frame) pueden esperar el ACK diferido
                    // del segmento anterior (paridad: H264MediaClient línea 60).
                    pairingSocket.tcpNoDelay = true
                    socket = pairingSocket
                    connectedHost = addr
                    startWriter(pairingSocket)
                    val reader = pairingSocket.getInputStream().bufferedReader()
                    readSessionLines(pairingSocket, reader)
                } catch (e: java.io.IOException) {
                    // Conexión rechazada o cortada: el `finally` entrega `null`
                    // y la máquina lo interpreta como E1 (SPEC-002 §9).
                    // (CancellationException se propaga sin catch: la cláusula
                    // explícita sería un RethrowCaughtException de detekt.)
                } finally {
                    // CR-056 (reintento U6): el cierre es POR INTENTO — si el
                    // intento vivo ya es otro socket, este `finally` no lo mata.
                    closeAttempt(pairingSocket)
                    if (socket == null) lineChannel.trySend(null)
                }
            }
    }

    private fun readSessionLines(
        s: Socket,
        reader: java.io.BufferedReader,
    ) {
        var finished = false
        while (!finished) {
            val line = readLineOrNull(reader)
            if (line == null) {
                finished = true
            } else {
                processSessionLine(s, line)
            }
        }
    }

    private fun processSessionLine(
        s: Socket,
        line: String,
    ) {
        if (!sessionMode.get() && line.contains("\"pair_ack\"")) {
            // Handshake OK (§7 paso 11): el MISMO hilo lector arma el watchdog
            // de silencio del heartbeat (CR-055 HB5).
            runCatching { s.soTimeout = sessionSilenceTimeoutMs }
            sessionMode.set(true)
            handshakeSocket = s
        }
        lineChannel.trySend(line)
    }

    private fun readLineOrNull(reader: java.io.BufferedReader): String? =
        try {
            reader.readLine()
        } catch (e: SocketTimeoutException) {
            // E1 en el handshake (SPEC-002 §9) o HB5 en sesión (CR-055):
            // sin ninguna línea durante el timeout, la conexión terminó.
            null
        }

    /** Inicia el único escritor: drena [pendingWrites] hacia `s` en FIFO. */
    private fun startWriter(s: Socket) {
        writerJob?.cancel()
        writerJob =
            scope.launch {
                try {
                    val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                    for (line in pendingWrites) {
                        // Escritor de un intento ya reemplazado: no consume la línea
                        // (sería escrita en un socket muerto). La devuelve a la cola
                        // para el escritor del intento vigente.
                        if (socket !== s) {
                            pendingWrites.trySend(line)
                            break
                        }
                        // Tráfico de sesión viejo (gamepad, video_config) nunca se escribe
                        // antes de pair_ack: el servidor exige pair_resume como primera
                        // línea y responde E4 a cualquier otra.
                        val isHandshake = HANDSHAKE_TYPES.any { line.contains("\"type\":\"$it\"") }
                        if (handshakeSocket !== s && !isHandshake) {
                            continue
                        }
                        writer.write(line)
                        writer.write("\n")
                        writer.flush()
                    }
                } catch (e: java.io.IOException) {
                    // El socket cerró con escrituras en vuelo: señal de fin.
                    closeAttempt(s)
                    if (socket == null) lineChannel.trySend(null)
                }
            }
    }

    /** Encola una línea NDJSON con `\n` final (SPEC-001 §4.2); el escritor
     * la transmite cuando la conexión esté lista. */
    fun send(line: String) {
        pendingWrites.trySend(line)
    }

    /** CR-055 (SPEC-001 §4.3 HB5): arma el watchdog de silencio al entrar en
     * U4 — sin ninguna línea durante [sessionSilenceTimeoutMs], la conexión
     * termina con el mismo fin observable que un corte. Best-effort: el
     * mecanismo en carrera es el del lector (`sessionMode`); si la UI ya entró
     * a Linked, el lector ya lo armó. */
    fun armSessionWatchdog() {
        sessionMode.set(true)
        runCatching { socket?.soTimeout = sessionSilenceTimeoutMs }
    }

    fun disconnect() {
        closeQuietly()
    }

    fun shutdown() {
        scope.cancel()
        closeQuietly()
    }

    /** Cierra el socket y descarta escrituras pendientes: sin conexión ya
     * no tienen destino (un intento abortado no deja basura para el siguiente). */
    private fun closeQuietly() {
        readerJob?.cancel()
        writerJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        connectedHost = null
        sessionMode.set(false)
        handshakeSocket = null
        while (true) {
            if (pendingWrites.tryReceive().getOrNull() == null) break
        }
    }

    /** CR-056 (reintento U6): cierre por intento. Solo muta el estado
     * compartido (socket/host/watchdog) si el socket que cierra sigue siendo
     * el vigente: el `finally` de un intento ANTIGUO nunca puede matar el
     * socket de un intento nuevo. */
    private fun closeAttempt(attemptSocket: Socket) {
        runCatching { attemptSocket.close() }
        if (socket === attemptSocket) {
            socket = null
            connectedHost = null
            sessionMode.set(false)
            handshakeSocket = null
            while (true) {
                if (pendingWrites.tryReceive().getOrNull() == null) break
            }
        }
    }
}
