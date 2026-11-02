package com.twinpad.android.app.core

import com.twinpad.android.app.wire.ClientHandshake
import com.twinpad.android.app.wire.HandshakeCodec
import com.twinpad.android.app.wire.HandshakeCrypto
import com.twinpad.android.app.wire.HandshakeMessage

/**
 * Estado de UI de la Pantalla de Pairing — U1–U5 de SPEC-002 §6.
 */
sealed class PairingUiState {
    /** U1 Inicio. */
    data object Idle : PairingUiState()

    /** U2 Escaneando. */
    data object Scanning : PairingUiState()

    /** U3 Handshake en curso. */
    data object Handshaking : PairingUiState()

    /** U4 Vinculado: única con sesión activa (SPEC-002 §14). */
    data class Linked(val sessionId: String) : PairingUiState()

    /** U6 Reconectando (CR-056, SPEC-001 §8.2/S4): la ligadura está retenida
     * en el servidor; la app intenta `pair_resume` mientras la ventana viva
     * (60 000 ms). El session_id NO cambia desde aquí.` */
    data class Reconnecting(val sessionId: String) : PairingUiState()

    /** U5 Error, con el error exacto de la tabla SPEC-002 §9. */
    data class Failure(val error: PairingError) : PairingUiState()
}

/**
 * Comandos declarativos que la máquina emite; los adaptadores (cámara, red,
 * UI) los ejecutan en orden (DES-002 §4).
 */
sealed class Effect {
    /** Solicitar permiso de cámara al SO. */
    data object RequestCamera : Effect()

    /** Abrir la cámara (entra a U2). */
    data object StartCamera : Effect()

    /** Liberar la cámara. */
    data object StopCamera : Effect()

    /** Abrir TCP a server_addr:server_port (SPEC-001 §7 paso 4). */
    data class Connect(val addr: String, val port: Int) : Effect()

    /** Cerrar la conexión TCP. */
    data object Disconnect : Effect()

    /** Enviar una línea NDJSON por la conexión activa. */
    data class SendLine(val line: String) : Effect()

    /** Mostrar el mensaje de error exacto de SPEC-002 §9 (U5). */
    data class ShowError(val error: PairingError) : Effect()
}

/**
 * Eventos de entrada de la máquina (DES-002 §4).
 */
sealed class PairingEvent {
    /** El usuario pulsa "escanear QR". */
    data object ScanRequested : PairingEvent()

    /** El SO concede el permiso de cámara. */
    data object CameraGranted : PairingEvent()

    /** El SO deniega el permiso (SPEC-002 §8.1). */
    data object CameraDenied : PairingEvent()

    /** La cámara decodifica un QR crudo. */
    data class QrScanned(val raw: String) : PairingEvent()

    /** Llegó una línea del servidor; `null` si la conexión cayó. */
    data class WireMessage(val line: String?) : PairingEvent()

    /** El usuario pulsa "cancelar" (U2/U3, SPEC-002 §8.2). */
    data object Cancelled : PairingEvent()

    /** La app va a segundo plano (SPEC-002 §8.3). */
    data object AppBackgrounded : PairingEvent()

    /** El usuario pulsa "reintentar" (U5→U1, y U1 con error previo→escanear). */
    data object Retry : PairingEvent()

    /** CR-056 (U6): tick del temporizador de reanudación programado por la
     * UI/VM mientras dura la retención del servidor (60 000 ms, §8.2).
     * `elapsedMs` se mide desde la caída del socket de control. */
    data class ResumeTick(val elapsedMs: Long) : PairingEvent()
}

/**
 * Máquina de estados pura de la Pantalla de Pairing (SPEC-002 §6, DES-002 §1).
 * Sin imports de Android ni de red: recibe [PairingEvent]s y emite [Effect]s.
 * Un intento activo a la vez (SPEC-002 R5).
 */
class PairingStateMachine {
    var state: PairingUiState = PairingUiState.Idle
        private set

    private var client: ClientHandshake? = null

    /** CR-056: dirección del último servidor conectado — U6 reconecta al
     * MISMO host:port sin re-escanear (§8.2). */
    private var lastConnect: Pair<String, Int>? = null

    /** Error previo recordado para "reintentar" desde U1 (SPEC-002 §6). */
    private var priorError: PairingError? = null

    fun sessionKeyOrNull(): ByteArray? = client?.sessionKeyOrNull()

    /** Avanza la máquina y devuelve los comandos a ejecutar en orden. */
    fun onEvent(event: PairingEvent): List<Effect> {
        val current = state
        return when (current) {
            PairingUiState.Idle -> onIdle(event)
            PairingUiState.Scanning -> onScanning(event)
            PairingUiState.Handshaking -> onHandshaking(event)
            is PairingUiState.Linked -> onLinked(event)
            is PairingUiState.Reconnecting -> onReconnecting(event, current)
            is PairingUiState.Failure -> onFailure(event, current)
        }
    }

    private fun onIdle(event: PairingEvent): List<Effect> =
        when (event) {
            is PairingEvent.ScanRequested -> listOf(Effect.RequestCamera)
            is PairingEvent.CameraGranted -> {
                state = PairingUiState.Scanning
                listOf(Effect.StartCamera)
            }
            is PairingEvent.CameraDenied -> {
                state = PairingUiState.Failure(PairingError.CameraDenied)
                listOf(Effect.ShowError(PairingError.CameraDenied))
            }
            // CR-060 (SPEC-002 §8.4): el código escrito entra en U1 con el
            // MISMO evento y validación que el QR, sin tocar la cámara.
            is PairingEvent.QrScanned -> onQrScanned(event.raw, fromCamera = false)
            is PairingEvent.Retry ->
                if (priorError != null) {
                    priorError = null
                    listOf(Effect.RequestCamera)
                } else {
                    emptyList()
                }
            else -> emptyList()
        }

    private fun onScanning(event: PairingEvent): List<Effect> =
        when (event) {
            is PairingEvent.QrScanned -> onQrScanned(event.raw, fromCamera = true)
            is PairingEvent.Cancelled -> cancelAttempt(from = PairingUiState.Scanning)
            is PairingEvent.AppBackgrounded -> cancelAttempt(from = PairingUiState.Scanning)
            is PairingEvent.CameraDenied -> {
                state = PairingUiState.Failure(PairingError.CameraDenied)
                listOf(Effect.StopCamera, Effect.ShowError(PairingError.CameraDenied))
            }
            else -> emptyList()
        }

    /** SPEC-002 §7 pasos 2–4: validar payload, conectar, enviar request.
     * CR-060 (§8.4): la misma ruta sirve al código escrito sin cámara
     * (`fromCamera = false`): solo se libera la cámara si en realidad se
     * abrió. Blancos al inicio/fin del payload se recortan; en el medio
     * siguen siendo E5 (§10). */
    private fun onQrScanned(
        raw: String,
        fromCamera: Boolean,
    ): List<Effect> {
        val cameraStop = if (fromCamera) listOf<Effect>(Effect.StopCamera) else emptyList()
        val payload =
            try {
                QrParser.parse(raw.trim())
            } catch (e: PairingException) {
                state = PairingUiState.Failure(e.error)
                return cameraStop + listOf(Effect.ShowError(e.error))
            }
        val (client, request) = ClientHandshake.start(payload.token)
        this.client = client
        // CR-056: recordado para la reanudación U6 (§8.2, mismo host:port).
        lastConnect = payload.serverAddr to payload.serverPort
        state = PairingUiState.Handshaking
        return cameraStop +
            listOf(
                Effect.Connect(payload.serverAddr, payload.serverPort),
                Effect.SendLine(HandshakeCodec.encode(request)),
            )
    }

    private fun onHandshaking(event: PairingEvent): List<Effect> =
        when (event) {
            is PairingEvent.WireMessage -> onWireMessage(event.line)
            is PairingEvent.Cancelled -> cancelAttempt(from = PairingUiState.Handshaking)
            is PairingEvent.AppBackgrounded -> cancelAttempt(from = PairingUiState.Handshaking)
            else -> emptyList()
        }

    /** SPEC-002 §7 pasos 4–6: challenge → proof → ack. */
    private fun onWireMessage(line: String?): List<Effect> {
        val active = client ?: return emptyList()
        if (line == null) {
            // El servidor cerró la conexión a mitad del Handshake (SPEC-002 §10).
            return failAttempt(PairingError.ServerUnavailable)
        }
        return try {
            when (val msg = HandshakeCodec.decode(line)) {
                is HandshakeMessage.Challenge -> {
                    val nonceS = HandshakeCrypto.decodeB64(msg.nonceS)
                    val nonceC = HandshakeCrypto.randomNonce()
                    val proof = active.onPairChallenge(nonceS, nonceC)
                    listOf(Effect.SendLine(HandshakeCodec.encode(proof)))
                }
                is HandshakeMessage.Ack -> {
                    active.onPairAck(msg.sessionId)
                    state = PairingUiState.Linked(msg.sessionId)
                    emptyList()
                }
                is HandshakeMessage.Error ->
                    failAttempt(wireCodeToPairingError(msg.code))
                is HandshakeMessage.Request, is HandshakeMessage.Proof,
                // CR-055: un ping/pong durante el Handshake también es fuera
                // de secuencia (SPEC-001 §9 E3).
                is HandshakeMessage.Ping,
                is HandshakeMessage.Pong,
                ->
                    // Mensaje fuera de secuencia (SPEC-001 §9 E3).
                    failAttempt(PairingError.Rejected)
                // CR-056: `pair_resume` es app→servidor; recibido por la app
                // es fuera de secuencia (E3).
                is HandshakeMessage.Resume -> failAttempt(PairingError.Rejected)
            }
        } catch (e: PairingException) {
            failAttempt(e.error)
        }
    }

    private fun onLinked(event: PairingEvent): List<Effect> =
        when (event) {
            is PairingEvent.WireMessage ->
                if (event.line == null) {
                    // SESIÓN: corte de conexión estando vinculado.
                    // CR-055 HB4/HB6: el heartbeat vencido llega aquí con la
                    // misma señal que un corte (sin error propio).
                    // CR-056 (SPEC-001 §8.2): NO exige re-scan si el servidor
                    // retiene la ligadura → U6 «Reconectando»; el temporizador
                    // de la VM alimenta [PairingEvent.ResumeTick] cada reintento.
                    val current = state as PairingUiState.Linked
                    state = PairingUiState.Reconnecting(current.sessionId)
                    emptyList()
                } else {
                    // CR-055 (SPEC-001 §4.3 HB2): responder `pong` con eco de
                    // `t`. Las demás líneas son tráfico de sesión posterior
                    // (fuera del alcance de SPEC-002).
                    val message = runCatching { HandshakeCodec.decode(event.line) }.getOrNull()
                    when (message) {
                        is HandshakeMessage.Ping ->
                            listOf(
                                Effect.SendLine(
                                    HandshakeCodec.encode(HandshakeMessage.Pong(message.t)),
                                ),
                            )
                        else -> emptyList()
                    }
                }
            else -> emptyList()
        }

    /** CR-056 (U6, SPEC-001 §8.2): reanudación automática de la sesión
     * retenida. Cada [PairingEvent.ResumeTick] re-conecta y envía
     * `pair_resume` con el mismo session_id + session_key (§4.2); el
     * temporizador lo programa la VM a un ritmo fijo. Vencida la ventana
     * (60 000 ms) o un E3 del servidor, la sesión termina (U1 con error,
     * exige re-scan). */
    private fun onReconnecting(
        event: PairingEvent,
        current: PairingUiState.Reconnecting,
    ): List<Effect> =
        when (event) {
            is PairingEvent.ResumeTick ->
                if (event.elapsedMs > resumeWindowMs) {
                    state = PairingUiState.Failure(PairingError.SessionEnded)
                    client = null
                    listOf(Effect.Disconnect, Effect.ShowError(PairingError.SessionEnded))
                } else {
                    val (addr, port) =
                        lastConnect
                            ?: return failAttempt(PairingError.SessionEnded)
                    val resumeLine = buildResumeLine(current.sessionId)
                    state = PairingUiState.Reconnecting(current.sessionId)
                    listOf(
                        Effect.Disconnect,
                        Effect.Connect(addr, port),
                        Effect.SendLine(resumeLine),
                    )
                }
            is PairingEvent.WireMessage -> onReconnectingWire(event.line)
            is PairingEvent.Cancelled -> cancelAttempt(from = state)
            else -> emptyList()
        }

    /** Línea recibida durante U6 (CR-056, §8.2): `pair_ack` con el session_id
     * retenido retoma la sesión; el resto es ruido del intento anterior y no
     * altera la máquina (el próximo `ResumeTick` reintenta). */
    private fun onReconnectingWire(line: String?): List<Effect> =
        when (line) {
            null -> emptyList() // intento en vuelo cayó: el próximo tick reintenta
            else ->
                try {
                    when (val msg = HandshakeCodec.decode(line)) {
                        is HandshakeMessage.Ack -> {
                            // §8.2: el mismo session_id retenido.
                            state = PairingUiState.Linked(msg.sessionId)
                            emptyList()
                        }
                        is HandshakeMessage.Error ->
                            failAttempt(PairingError.SessionEnded)
                        else -> emptyList()
                    }
                } catch (e: PairingException) {
                    emptyList() // línea ruidosa del intento anterior: sin efecto
                }
        }

    /** Construye `pair_resume` (§4.2) con nonce fresco + proof HMAC sobre el
     * dominio fijo usando la session_key ORIGINAL de la ligadura. */
    private fun buildResumeLine(sessionId: String): String {
        val key =
            client?.sessionKeyOrNull()
                ?: throw PairingException(PairingError.SessionEnded)
        val sessionIdBytes = HandshakeCrypto.decodeB64(sessionId)
        val nonceC = HandshakeCrypto.randomNonce()
        val proof = HandshakeCrypto.computeResumeProof(key, sessionIdBytes, nonceC)
        return HandshakeCodec.encode(
            HandshakeMessage.Resume(
                sessionId = sessionId,
                nonceC = HandshakeCrypto.encodeB64(nonceC),
                proof = HandshakeCrypto.encodeB64(proof),
            ),
        )
    }

    private fun onFailure(
        event: PairingEvent,
        current: PairingUiState.Failure,
    ): List<Effect> =
        when (event) {
            is PairingEvent.Retry -> {
                // U5→U1 (SPEC-002 §6): limpia el error; el usuario re-escanea.
                priorError = current.error
                state = PairingUiState.Idle
                emptyList()
            }
            else -> emptyList()
        }

    /** Ventana de retención del servidor (CR-056, SPEC-001 §8.2): 60 000 ms.
     * Paridad con `core::resume::RETENTION_MS`. */
    private val resumeWindowMs: Long = 60_000L

    /** Aborta el intento y libera los recursos activos (SPEC-002 §8.2/§8.3). */
    private fun cancelAttempt(from: PairingUiState): List<Effect> {
        state = PairingUiState.Idle
        client = null
        priorError = null
        return when (from) {
            PairingUiState.Scanning -> listOf(Effect.StopCamera)
            else -> listOf(Effect.Disconnect)
        }
    }

    private fun failAttempt(error: PairingError): List<Effect> {
        state = PairingUiState.Failure(error)
        client = null
        return listOf(Effect.Disconnect, Effect.ShowError(error))
    }
}
