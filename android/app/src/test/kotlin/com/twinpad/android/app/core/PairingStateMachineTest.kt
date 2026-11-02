package com.twinpad.android.app.core

import com.twinpad.android.app.wire.HandshakeCodec
import com.twinpad.android.app.wire.HandshakeCrypto
import com.twinpad.android.app.wire.HandshakeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TEST-010..014 — verifies SPEC-002 (§7, §8, §9, §10) con sockets de
 * loopback reales; el protocolo no se mockea (test-strategy §10).
 */
class PairingStateMachineTest {
    // ---- vectores compartidos con core (paridad DES-002 §11) ----

    private val tokenBytes = ByteArray(32) { 7 }
    private val tokenStr = HandshakeCrypto.encodeB64(tokenBytes)
    private val validQr = "twinpad://v1#s=127.0.0.1&p=8420&t=$tokenStr"

    /** session_id conforme: 16 bytes base64url (SPEC-001 §4.2). */
    private val sessionId = HandshakeCrypto.encodeB64(ByteArray(16) { 5 })

    private fun machineIn(vararg events: PairingEvent): PairingStateMachine {
        val m = PairingStateMachine()
        events.forEach { m.onEvent(it) }
        return m
    }

    private fun scanningMachine(): PairingStateMachine =
        machineIn(
            PairingEvent.ScanRequested,
            PairingEvent.CameraGranted,
        )

    // ---- TEST-010 — verifies SPEC-002 (§7: flujo completo hasta U4) ----

    @Test
    fun `full pairing flow reaches Linked with server verified proof`() {
        val m = scanningMachine()
        val qr = "twinpad://v1#s=127.0.0.1&p=8420&t=$tokenStr"

        m.onEvent(PairingEvent.QrScanned(qr))
        assertEquals(PairingUiState.Handshaking, m.state)

        // pair_request canónico salió por el efecto Connect/SendLine.
        val nonceS = ByteArray(32) { 11 }
        val challenge =
            HandshakeCodec.encode(
                HandshakeMessage.Challenge(HandshakeCrypto.encodeB64(nonceS)),
            )
        val proofEffect =
            m.onEvent(PairingEvent.WireMessage(challenge))
                .filterIsInstance<Effect.SendLine>().single()

        // El servidor (oráculo con HMAC independiente) verifica el proof.
        verifyProofServerSide(
            request = """{"type":"pair_request","schema":"twinpad.v1","token":"$tokenStr"}""",
            challenge = challenge,
            proofLine = proofEffect.line,
            nonceS = nonceS,
        )

        m.onEvent(PairingEvent.WireMessage(HandshakeCodec.encode(HandshakeMessage.Ack(sessionId))))
        assertEquals(PairingUiState.Linked(sessionId), m.state)
    }

    // ---- TEST-0130 — CR-056 (SPEC-002 §6/§9: corte en U4 → U6 Reconectando,
    // reintento automático ≤ 60 s sin re-escanear el QR; helper: linkedMachine) ----

    @Test
    fun `control channel drop in Linked enters Reconnecting U6`() {
        val m = linkedMachine()

        val effects = m.onEvent(PairingEvent.WireMessage(null))
        assertEquals(PairingUiState.Reconnecting(sessionId), m.state)
        assertTrue(effects.isEmpty()) // el reintento lo programa el temporizador
    }

    @Test
    fun `resume tick reconnects and sends canonical pair_resume`() {
        val m = linkedMachine()
        m.onEvent(PairingEvent.WireMessage(null))

        val effects = m.onEvent(PairingEvent.ResumeTick(elapsedMs = 2_500))
        val connect = effects.filterIsInstance<Effect.Connect>().single()
        assertEquals("127.0.0.1", connect.addr) // mismo host:port, sin QR
        assertEquals(8420, connect.port)
        val resume =
            HandshakeCodec.decode(
                effects.filterIsInstance<Effect.SendLine>().single().line,
            ) as HandshakeMessage.Resume
        assertEquals(sessionId, resume.sessionId)

        // Oráculo server-side: proof = HMAC(session_key, "twinpad.v1 resume" ‖ sid ‖ nonce_c).
        val token = Base64.getUrlDecoder().decode(tokenStr)
        val prk = hmac(ByteArray(32) { 11 }, token)
        val sessionKey = hmac(prk, "twinpad.v1 session".toByteArray() + byteArrayOf(0x01))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update("twinpad.v1 resume".toByteArray())
        mac.update(HandshakeCrypto.decodeB64(resume.sessionId))
        mac.update(HandshakeCrypto.decodeB64(resume.nonceC))
        assertTrue(mac.doFinal().contentEquals(HandshakeCrypto.decodeB64(resume.proof)))
    }

    @Test
    fun `resume accepted with same session id returns to Linked U4`() {
        val m = linkedMachine()
        m.onEvent(PairingEvent.WireMessage(null))
        m.onEvent(PairingEvent.ResumeTick(elapsedMs = 2_500))

        // §8.2: el server entrega pair_ack con el MISMO session_id.
        m.onEvent(
            PairingEvent.WireMessage(
                HandshakeCodec.encode(HandshakeMessage.Ack(sessionId)),
            ),
        )
        assertEquals(PairingUiState.Linked(sessionId), m.state)
    }

    @Test
    fun `resume window expiry forces rescan with session ended`() {
        val m = linkedMachine()
        m.onEvent(PairingEvent.WireMessage(null))

        val effects = m.onEvent(PairingEvent.ResumeTick(elapsedMs = 60_001))
        assertEquals(PairingUiState.Failure(PairingError.SessionEnded), m.state)
        assertTrue(effects.any { it is Effect.Disconnect })
    }

    @Test
    fun `resume rejected by server maps to session ended`() {
        val m = linkedMachine()
        m.onEvent(PairingEvent.WireMessage(null))
        m.onEvent(PairingEvent.ResumeTick(elapsedMs = 2_500))

        val effects =
            m.onEvent(
                PairingEvent.WireMessage(
                    HandshakeCodec.encode(
                        HandshakeMessage.Error(code = "E3", message = "rejected"),
                    ),
                ),
            )
        assertEquals(PairingUiState.Failure(PairingError.SessionEnded), m.state)
        assertTrue(effects.any { it is Effect.Disconnect })
    }

    /** Verificación del lado servidor: proof = HMAC(session_key, transcript). */
    private fun verifyProofServerSide(
        request: String,
        challenge: String,
        proofLine: String,
        nonceS: ByteArray,
    ) {
        val proofMsg = HandshakeCodec.decode(proofLine) as HandshakeMessage.Proof
        val token = Base64.getUrlDecoder().decode(tokenStr)
        val prk = hmac(nonceS, token)
        val sessionKey = hmac(prk, "twinpad.v1 session".toByteArray() + byteArrayOf(0x01))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update(request.toByteArray())
        mac.update(challenge.toByteArray())
        mac.update(nonceS)
        val proofBytes = HandshakeCrypto.decodeB64(proofMsg.proof)
        // El nonce_c usado por la máquina es aleatorio: re-computar con él.
        val nonceC = HandshakeCrypto.decodeB64(proofMsg.nonceC)
        mac.update(nonceC)
        val expected = mac.doFinal()
        assertTrue(expected.contentEquals(proofBytes))
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(data)
        return mac.doFinal()
    }

    // ---- TEST-011 — verifies SPEC-002 (§9 E1: servidor no disponible) ----

    @Test
    fun `connection closed by server mid handshake maps to E1`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        assertEquals(PairingUiState.Handshaking, m.state)

        val effects = m.onEvent(PairingEvent.WireMessage(null))
        assertEquals(PairingUiState.Failure(PairingError.ServerUnavailable), m.state)
        assertTrue(effects.any { it is Effect.Disconnect })
        assertTrue(effects.any { it is Effect.ShowError && it.error == PairingError.ServerUnavailable })
    }

    // ---- TEST-012 — verifies SPEC-002 (§9 E5 y §8.1 PERMISO) ----

    @Test
    fun `invalid qr produces E5 without any connection attempt`() {
        val m = scanningMachine()
        val effects = m.onEvent(PairingEvent.QrScanned("https://otra-app#x=1"))
        assertEquals(PairingUiState.Failure(PairingError.QrInvalid), m.state)
        assertTrue(effects.any { it is Effect.StopCamera })
        assertFalse(effects.any { it is Effect.Connect })
        assertFalse(effects.any { it is Effect.SendLine })
    }

    @Test
    fun `camera denied maps to PERMISO error and stays out of scanning`() {
        val m = PairingStateMachine()
        m.onEvent(PairingEvent.ScanRequested)
        m.onEvent(PairingEvent.CameraDenied)
        assertEquals(PairingUiState.Failure(PairingError.CameraDenied), m.state)
    }

    // ---- TEST-0124 — verifies SPEC-002 (§8.4: entrada manual sin cámara) ----

    @Test
    fun `typed code from idle reaches Handshaking without any camera effect`() {
        val m = PairingStateMachine()
        val effects = m.onEvent(PairingEvent.QrScanned(validQr))
        assertEquals(PairingUiState.Handshaking, m.state)

        // La vía del código escrito jamás toca la cámara (SPEC-002 §11-R20).
        assertFalse(effects.any { it is Effect.RequestCamera })
        assertFalse(effects.any { it is Effect.StartCamera })
        assertFalse(effects.any { it is Effect.StopCamera })

        // Mismo resultado del §7: Connect + pair_request (SPEC-001 §7 paso 5).
        val connect = effects.filterIsInstance<Effect.Connect>().single()
        assertEquals("127.0.0.1", connect.addr)
        assertEquals(8420, connect.port)
        val request = effects.filterIsInstance<Effect.SendLine>().single()
        val decoded = HandshakeCodec.decode(request.line)
        assertTrue(decoded is HandshakeMessage.Request)
    }

    @Test
    fun `invalid typed code from idle produces E5 without opening a connection`() {
        val m = PairingStateMachine()
        val effects = m.onEvent(PairingEvent.QrScanned("texto sin scheme twinpad://v1#s=1"))
        assertEquals(PairingUiState.Failure(PairingError.QrInvalid), m.state)
        assertFalse(effects.any { it is Effect.Connect })
        assertFalse(effects.any { it is Effect.SendLine })
        assertFalse(effects.any { it is Effect.StopCamera })
    }

    @Test
    fun `typed code edge blanks are trimmed and inner blanks are E5`() {
        // Blancos al inicio/fin: se recortan antes de validar (§10, CR-060).
        val trimmed = PairingStateMachine()
        trimmed.onEvent(PairingEvent.QrScanned("   $validQr   \n"))
        assertEquals(PairingUiState.Handshaking, trimmed.state)

        // Blancos en el medio: E5 sin abrir conexión.
        val inner = PairingStateMachine()
        inner.onEvent(PairingEvent.QrScanned("twinpad://v1#s=127.0.0.1&p=8420&t=$tokenStr\n "))
        assertEquals(PairingUiState.Handshaking, inner.state)
        val spaced = PairingStateMachine()
        spaced.onEvent(PairingEvent.QrScanned("twinpad://v1#s=127.0.0.1 &p=8420&t=$tokenStr"))
        assertEquals(PairingUiState.Failure(PairingError.QrInvalid), spaced.state)
    }

    // ---- TEST-013 — verifies SPEC-002 (§9 E2/E3 desde pair_error) ----

    @Test
    fun `pair_error E2 maps to QR expired message`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        val wire =
            HandshakeCodec.encode(
                HandshakeMessage.Error("E2", "QR expirado, generar uno nuevo"),
            )
        m.onEvent(PairingEvent.WireMessage(wire))
        assertEquals(PairingUiState.Failure(PairingError.QrExpired), m.state)
    }

    @Test
    fun `pair_error E3 maps to rejected message`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        val wire = HandshakeCodec.encode(HandshakeMessage.Error("E3", "Vinculación rechazada"))
        m.onEvent(PairingEvent.WireMessage(wire))
        assertEquals(PairingUiState.Failure(PairingError.Rejected), m.state)
    }

    // ---- TEST-014 — verifies SPEC-002 (§10: edge cases) ----

    @Test
    fun `cancel during scanning stops camera and returns to idle`() {
        val m = scanningMachine()
        val effects = m.onEvent(PairingEvent.Cancelled)
        assertEquals(PairingUiState.Idle, m.state)
        assertTrue(effects.any { it is Effect.StopCamera })
    }

    @Test
    fun `background during handshake disconnects cleanly`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        val effects = m.onEvent(PairingEvent.AppBackgrounded)
        assertEquals(PairingUiState.Idle, m.state)
        assertTrue(effects.any { it is Effect.Disconnect })
    }

    @Test
    fun `unknown pair_error code is treated as E4`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        // E9 no es un código válido del wire (SPEC-001 §4.2) → el codec
        // rechaza el mensaje completo como E4.
        val wire = """{"type":"pair_error","code":"E9","message":"x"}"""
        m.onEvent(PairingEvent.WireMessage(wire))
        assertEquals(PairingUiState.Failure(PairingError.InvalidResponse), m.state)
    }

    @Test
    fun `out of sequence message in handshaking is rejected`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        // Un pair_ack bien formado antes del challenge no es parte de §7.
        m.onEvent(PairingEvent.WireMessage(HandshakeCodec.encode(HandshakeMessage.Ack(sessionId))))
        assertEquals(PairingUiState.Failure(PairingError.Rejected), m.state)
    }

    @Test
    fun `retry from failure returns to idle and rescan starts new attempt`() {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                // E5: payload no conforme (SPEC-002 §9).
                PairingEvent.QrScanned("https://otro#x=1"),
            )
        m.onEvent(PairingEvent.Retry)
        assertEquals(PairingUiState.Idle, m.state)
        m.onEvent(PairingEvent.ScanRequested)
        assertEquals(PairingUiState.Idle, m.state) // espera permiso
        m.onEvent(PairingEvent.CameraGranted)
        assertEquals(PairingUiState.Scanning, m.state)
    }

    @Test
    fun `reusing a linked session id after re-scan is server-side E3`() {
        // Segundo intento con el mismo QR tras una vinculación (R2 de SPEC-001):
        // el rechazo llega como pair_error E3 del servidor.
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
            )
        m.onEvent(PairingEvent.WireMessage(HandshakeCodec.encode(HandshakeMessage.Error("E3", "usado"))))
        assertEquals(PairingUiState.Failure(PairingError.Rejected), m.state)
    }

    private fun linkedMachine(): PairingStateMachine {
        val m =
            machineIn(
                PairingEvent.ScanRequested,
                PairingEvent.CameraGranted,
                PairingEvent.QrScanned(validQr),
                // §7 completo: challenge → proof (ignorado) → ack.
                PairingEvent.WireMessage(
                    HandshakeCodec.encode(
                        HandshakeMessage.Challenge(HandshakeCrypto.encodeB64(ByteArray(32) { 11 })),
                    ),
                ),
                PairingEvent.WireMessage(HandshakeCodec.encode(HandshakeMessage.Ack(sessionId))),
            )
        assertEquals(PairingUiState.Linked(sessionId), m.state)
        return m
    }

    // —— TEST-0122 — verifies SPEC-001 §4.3 HB2/HB5 (heartbeat, CR-055) ——

    /** HB2: el `ping` del Servidor se responde con `pong` que ecúa el `t`. */
    @Test
    fun `linked machine answers ping with pong echoing t`() {
        val m = linkedMachine()
        val effects = m.onEvent(PairingEvent.WireMessage("""{"type":"ping","t":7}"""))
        assertEquals(PairingUiState.Linked(sessionId), m.state)
        val pong = effects.filterIsInstance<Effect.SendLine>().single()
        assertEquals("""{"type":"pong","t":7}""", pong.line)
    }

    /** HB5: el silencio (la misma señal `null` que un corte) YA NO termina la
     * sesión: CR-056 (SPEC-001 §8.2/S4) entra a U6 «Reconectando» y la ventana
     * de retención decide el fin (TEST-0130). De ahí el cambio de historia. */
    @Test
    fun `silence in linked session enters Reconnecting U6`() {
        val m = linkedMachine()
        m.onEvent(PairingEvent.WireMessage(null))
        assertEquals(PairingUiState.Reconnecting(sessionId), m.state)
    }

    /** El pong no altera el estado; otro tráfico de sesión pasa sin efecto. */
    @Test
    fun `pong and unrelated session traffic keep linked state unchanged`() {
        val m = linkedMachine()
        m.onEvent(PairingEvent.WireMessage("""{"type":"pong","t":1}"""))
        m.onEvent(PairingEvent.WireMessage("""{"type":"stream_start","width":640}"""))
        assertEquals(PairingUiState.Linked(sessionId), m.state)
    }
}
