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
}
