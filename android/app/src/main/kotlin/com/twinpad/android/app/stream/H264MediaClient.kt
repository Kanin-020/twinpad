package com.twinpad.android.app.stream

import com.twinpad.android.app.wire.MediaCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 2_000
private const val NONCE_BYTES = 32
private const val MEDIA_HEADER_BYTES = Int.SIZE_BYTES
private const val MEDIA_COUNTER_BYTES = Long.SIZE_BYTES
private const val MEDIA_MIN_RECORD_BYTES = MEDIA_COUNTER_BYTES + MediaCrypto.TAG_BYTES
private const val UINT32_MAX_AS_LONG = 0xffff_ffffL
private const val FRAME_METADATA_BYTES = Long.SIZE_BYTES * 2 + Byte.SIZE_BYTES
private const val IDR_FLAG_MASK = 1
private const val RESERVED_FLAG_MASK = 0xfe
private const val BYTE_SHIFT = 8
private const val BYTE_MASK = 0xff

/** One authenticated media connection; caller owns its lifecycle and Surface decoder. */
class H264MediaClient(
    private val host: String,
    private val sessionId: ByteArray,
    private val sessionKey: ByteArray,
    private val port: Int = MediaCrypto.PORT,
    private val onAccessUnit: (EncodedAccessUnit) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val running = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null

    data class EncodedAccessUnit(
        val frameNumber: Long,
        val timestampUs: Long,
        val idr: Boolean,
        val bytes: ByteArray,
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::runClient, "twinpad-media-reader").apply { start() }
    }

    fun close() {
        running.set(false)
        runCatching { socket?.close() }
        worker?.interrupt()
    }

    private fun runClient() {
        try {
            val connection = Socket()
            socket = connection
            connection.tcpNoDelay = true
            connection.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            connection.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(connection.getInputStream())
            val output = DataOutputStream(connection.getOutputStream())
            val nonceC = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            output.write(sessionId)
            output.write(nonceC)
            output.write(MediaCrypto.clientProof(sessionKey, sessionId, nonceC))
            output.flush()
            val nonceS = ByteArray(NONCE_BYTES)
            val serverProof = ByteArray(NONCE_BYTES)
            input.readFully(nonceS)
            input.readFully(serverProof)
            check(MediaCrypto.verify(MediaCrypto.serverProof(sessionKey, sessionId, nonceC, nonceS), serverProof))
            val mediaKey = MediaCrypto.deriveMediaKey(sessionKey, nonceC, nonceS)
            var expectedCounter = 0L
            var lastFrame = -1L
            var lastTimestamp = -1L
            while (running.get()) {
                val length = input.readInt().toLong() and UINT32_MAX_AS_LONG
                require(length in MEDIA_MIN_RECORD_BYTES.toLong()..MediaCrypto.MAX_RECORD_BYTES.toLong())
                val record = ByteArray(MEDIA_HEADER_BYTES + length.toInt())
                record[0] = (length ushr (BYTE_SHIFT * (MEDIA_HEADER_BYTES - 1))).toByte()
                record[1] = (length ushr (BYTE_SHIFT * (MEDIA_HEADER_BYTES - 2))).toByte()
                record[2] = (length ushr BYTE_SHIFT).toByte()
                record[MEDIA_HEADER_BYTES - 1] = length.toByte()
                input.readFully(record, MEDIA_HEADER_BYTES, length.toInt())
                val payload = MediaCrypto.openRecord(mediaKey, expectedCounter, record)
                expectedCounter = Math.addExact(expectedCounter, 1L)
                require(payload.size > FRAME_METADATA_BYTES)
                val frame = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.BIG_ENDIAN)
                val frameNumber = frame.long
                val timestampUs = frame.long
                val flags = frame.get().toInt() and BYTE_MASK
                require(flags and RESERVED_FLAG_MASK == 0 && frameNumber > lastFrame && timestampUs > lastTimestamp)
                val isIdr = flags and IDR_FLAG_MASK != 0
                val bytes = ByteArray(frame.remaining()).also(frame::get)
                require(bytes.isNotEmpty())
                onAccessUnit(EncodedAccessUnit(frameNumber, timestampUs, isIdr, bytes))
                lastFrame = frameNumber
                lastTimestamp = timestampUs
            }
        } catch (_: Exception) {
            // The caller reports inactive/reconnecting state; invalid records are never delivered.
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            onClosed()
        }
    }
}
