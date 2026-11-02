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
private const val BYTE_MASK = 0xff

/** Conexión media TCP autenticada (SPEC-003 §4.2): handshake con proofs
 * cruzados, records GCM con counter estricto y entrega de AU H.264. El
 * caller posee el ciclo de vida y el decodificador (MediaClient). */
internal class H264MediaClient(
    private val host: String,
    private val sessionId: ByteArray,
    private val sessionKey: ByteArray,
    private val port: Int = MediaCrypto.PORT,
    private val onAccessUnit: (EncodedAccessUnit) -> Unit,
    private val onClosed: () -> Unit,
) : MediaClient {
    private val running = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null

    /** AU H.264 ya autenticada y ordenada (fn/ts estrictos R2). */
    data class EncodedAccessUnit(
        /** `fn` del frame (mono creciente, §10). */
        val frameNumber: Long,
        /** PTS en µs (creciente junto a `fn`). */
        val timestampUs: Long,
        /** Entra con IDR (flag §4.2); decide H264SurfaceDecoder. */
        val idr: Boolean,
        /** Anexo B crudo (SPS/PPS/…); sin re-codificación. */
        val bytes: ByteArray,
    )

    /** Arranca el hilo lector una única vez (idempotente). */
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::runClient, "twinpad-media-reader").apply { start() }
    }

    /** Corta el socket y despierta el hilo; reentrante. */
    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        worker?.interrupt()
    }

    /** Loop del hilo lector: handshake una vez, luego records hasta
     * `close()`. Cualquier violación (framing, GCM, orden) termina el
     * cliente sin entregar lo inválido; el caller cae a reconexión. */
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
                val record =
                    java.nio.ByteBuffer
                        .allocate(MEDIA_HEADER_BYTES + length.toInt())
                        .order(java.nio.ByteOrder.BIG_ENDIAN)
                        .putInt(length.toInt())
                val recordBytes = record.array()
                input.readFully(recordBytes, MEDIA_HEADER_BYTES, length.toInt())
                val payload = MediaCrypto.openRecord(mediaKey, expectedCounter, recordBytes)
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
            // El caller marca inactivo/reconectando; lo inválido jamás se entrega.
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            onClosed()
        }
    }
}
