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

    /** Error previo recordado para "reintentar" desde U1 (SPEC-002 §6). */
    private var priorError: PairingError? = null

    /** Avanza la máquina y devuelve los comandos a ejecutar en orden. */
    fun onEvent(event: PairingEvent): List<Effect> {
        val current = state
        return when (current) {
            PairingUiState.Idle -> onIdle(event)
            PairingUiState.Scanning -> onScanning(event)
            PairingUiState.Handshaking -> onHandshaking(event)
            is PairingUiState.Linked -> onLinked(event)
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
            is PairingEvent.QrScanned -> onQrScanned(event.raw)
            is PairingEvent.Cancelled -> cancelAttempt(from = PairingUiState.Scanning)
            is PairingEvent.AppBackgrounded -> cancelAttempt(from = PairingUiState.Scanning)
            is PairingEvent.CameraDenied -> {
                state = PairingUiState.Failure(PairingError.CameraDenied)
                listOf(Effect.StopCamera, Effect.ShowError(PairingError.CameraDenied))
            }
            else -> emptyList()
        }

    /** SPEC-002 §7 pasos 2–4: validar payload, conectar, enviar request. */
    private fun onQrScanned(raw: String): List<Effect> {
        val payload =
            try {
                QrParser.parse(raw)
            } catch (e: PairingException) {
                state = PairingUiState.Failure(e.error)
                return listOf(Effect.StopCamera, Effect.ShowError(e.error))
            }
        val (client, request) = ClientHandshake.start(payload.token)
        this.client = client
        state = PairingUiState.Handshaking
        return listOf(
            Effect.StopCamera,
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
                is HandshakeMessage.Request, is HandshakeMessage.Proof ->
                    // Mensaje fuera de secuencia (SPEC-001 §9 E3).
                    failAttempt(PairingError.Rejected)
            }
        } catch (e: PairingException) {
            failAttempt(e.error)
        }
    }

    private fun onLinked(event: PairingEvent): List<Effect> =
        when (event) {
            is PairingEvent.WireMessage ->
                if (event.line == null) {
                    // SESIÓN: corte de conexión estando vinculado (SPEC-002 §9).
                    state = PairingUiState.Failure(PairingError.SessionEnded)
                    listOf(Effect.ShowError(PairingError.SessionEnded))
                } else {
                    // Tráfico de sesión posterior: fuera del alcance de SPEC-002.
                    emptyList()
                }
            else -> emptyList()
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
