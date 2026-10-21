package com.twinpad.android.app.wire

import com.twinpad.android.app.pairing.MediaHandshakeContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** TEST-071 — verifies SPEC-003/CR-014 Kotlin HMAC and HKDF protocol parity. */
class MediaCryptoTest {
    @Test
    fun hmac_proofs_and_media_key_match_cross_language_vectors() {
        val sessionKey = ByteArray(32) { it.toByte() }
        val sessionId = ByteArray(16) { it.toByte() }
        val nonceC = ByteArray(32) { (it + 32).toByte() }
        val nonceS = ByteArray(32) { (it + 64).toByte() }

        assertArrayEquals(
            "f1c88e0c68a0464203655f9cb7c617a032fda319ac665a933d0da6c0577cd348".hexBytes(),
            MediaCrypto.clientProof(sessionKey, sessionId, nonceC),
        )
        assertArrayEquals(
            "42e565590a5ae737a1ae43b61e107f85f4eb9ba60c23738eb82400b393ae5278".hexBytes(),
            MediaCrypto.serverProof(sessionKey, sessionId, nonceC, nonceS),
        )
        assertArrayEquals(
            "6f95e990fd3a7f0556c0350a4edcc9ea8e5010d40ee02078e7d2373e03bd36c1".hexBytes(),
            MediaCrypto.deriveMediaKey(sessionKey, nonceC, nonceS),
        )
    }

    @Test
    fun authenticated_loopback_channel_validates_handshake_and_delivers_only_valid_records() {
        val context = handshakeContext()
        val source = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        val payload = mediaPayload(source)
        val listener = ServerSocket(0)
        val server = Thread { serveAuthenticatedMedia(listener, context, payload) }.apply { start() }

        Socket("127.0.0.1", listener.localPort).use { client ->
            client.soTimeout = 3_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            writeClientProof(output, context)
            val receivedNonce = ByteArray(32).also(input::readFully)
            val serverProof = ByteArray(32).also(input::readFully)
            val expectedProof =
                MediaCrypto.serverProof(context.sessionKey, context.sessionId, context.clientNonce, receivedNonce)
            assertTrue(MediaCrypto.verify(expectedProof, serverProof))
            assertValidAndTamperedMediaRecords(input, payload, context.mediaKey)
        }
        server.join(3_000)
        listener.close()
        assertFalse(server.isAlive)
    }

    private fun serveAuthenticatedMedia(
        listener: ServerSocket,
        context: MediaHandshakeContext,
        payload: ByteArray,
    ) {
        listener.accept().use { socket ->
            socket.soTimeout = 3_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val receivedSession = ByteArray(16).also(input::readFully)
            val receivedNonce = ByteArray(32).also(input::readFully)
            val clientProof = ByteArray(32).also(input::readFully)
            check(receivedSession.contentEquals(context.sessionId))
            check(receivedNonce.contentEquals(context.clientNonce))
            val expectedProof = MediaCrypto.clientProof(context.sessionKey, context.sessionId, context.clientNonce)
            check(MessageDigest.isEqual(expectedProof, clientProof))
            output.write(context.serverNonce)
            output.write(
                MediaCrypto.serverProof(
                    context.sessionKey,
                    context.sessionId,
                    context.clientNonce,
                    context.serverNonce,
                ),
            )
            output.write(encryptRecord(context.mediaKey, 0, payload))
            val tamperedRecord = encryptRecord(context.mediaKey, 1, payload)
            tamperedRecord[tamperedRecord.lastIndex] = (tamperedRecord.last().toInt() xor 1).toByte()
            output.write(tamperedRecord)
            output.flush()
            Thread.sleep(100)
        }
    }

    private fun writeClientProof(
        output: DataOutputStream,
        context: MediaHandshakeContext,
    ) {
        output.write(context.sessionId)
        output.write(context.clientNonce)
        output.write(MediaCrypto.clientProof(context.sessionKey, context.sessionId, context.clientNonce))
        output.flush()
    }

    private fun assertValidAndTamperedMediaRecords(
        input: DataInputStream,
        payload: ByteArray,
        key: ByteArray,
    ) {
        val goodRecord = readRecord(input)
        assertArrayEquals(payload, MediaCrypto.openRecord(key, 0, goodRecord))
        assertEquals(0L, ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long)
        val badRecord = readRecord(input)
        assertFalse(runCatching { MediaCrypto.openRecord(key, 1, badRecord) }.isSuccess)
    }

    private fun readRecord(input: DataInputStream): ByteArray {
        val length = input.readInt()
        return ByteArray(4 + length).also { record ->
            ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN).putInt(length)
            input.readFully(record, 4, length)
        }
    }

    private fun handshakeContext(): MediaHandshakeContext {
        val sessionKey = ByteArray(32) { (it * 3).toByte() }
        val sessionId = ByteArray(16) { (it + 1).toByte() }
        val clientNonce = ByteArray(32) { (it + 33).toByte() }
        val serverNonce = ByteArray(32) { (it + 91).toByte() }
        val mediaKey = MediaCrypto.deriveMediaKey(sessionKey, clientNonce, serverNonce)
        return MediaHandshakeContext(sessionId, sessionKey, clientNonce, serverNonce, mediaKey)
    }

    private fun mediaPayload(source: ByteArray): ByteArray =
        ByteBuffer.allocate(17 + source.size).order(ByteOrder.BIG_ENDIAN)
            .putLong(0).putLong(1234).put(1).put(source).array()

    private fun encryptRecord(
        key: ByteArray,
        counter: Long,
        plaintext: ByteArray,
    ): ByteArray {
        val length = 8 + plaintext.size + MediaCrypto.TAG_BYTES
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(length).putLong(counter).array()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(0).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
