package com.twinpad.android.app.stream

import com.twinpad.android.app.wire.MediaCrypto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher

/** CR-075: eventos de la conexión media preferente (un solo callback). */
internal sealed interface UdpPreferredEvent {
    /** Transporte activo decidido a los 900 ms (true=UDP, false=TCP). */
    data class TransportActive(val isUdp: Boolean) : UdpPreferredEvent

    /** Canal muerto sin fallback viable (§8.5). */
    data object Closed : UdpPreferredEvent
}

/**
 * CR-062 (SPEC-003 §4.4): cliente media UDP preferente con fallback TCP
 * (§8.5). Mantiene el `DatagramSocket` conectado al `media_port` (8425),
 * envía el JOIN autenticado de 92 B (§4.2) y reenvía el keepalive del
 * MISMO nonce cada 5 s con el `last_seq` del último datagrama aceptado
 * (señal R11 de la escalera de CR-058). Si en 900 ms no llega ningún
 * datagrama autenticado, entrega el canal al TCP para esa conexión; el
 * `PairingMediaSession` recrea el cliente ante cada cierre.
 */
internal class UdpPreferredMediaClient(
    host: String,
    sessionId: ByteArray,
    sessionKey: ByteArray,
    port: Int = MediaCrypto.PORT,
    onAccessUnit: (H264MediaClient.EncodedAccessUnit) -> Unit,
    /** CR-075: transporte activo y cierres, en un solo callback por detekt. */
    private val onEvent: (UdpPreferredEvent) -> Unit,
) : MediaClient {
    private val fallbackStarted = AtomicBoolean(false)
    private val closedDelivered = AtomicBoolean(false)

    private val udp =
        UdpMediaClient(
            host = host,
            sessionId = sessionId,
            sessionKey = sessionKey,
            port = port,
            onAccessUnit = onAccessUnit,
            onClosed = ::onUdpClosed,
        )

    private val tcp =
        H264MediaClient(
            host = host,
            sessionId = sessionId,
            sessionKey = sessionKey,
            port = port,
            onAccessUnit = onAccessUnit,
            onClosed = ::onTcpClosed,
        )

    private val fallbackWatch =
        Thread(
            {
                runFallbackWatch()
            },
            "twinpad-media-fallback",
        )

    override fun start() {
        udp.start()
        fallbackWatch.start()
    }

    override fun close() {
        fallbackWatch.interrupt()
        udp.close()
        tcp.close()
    }

    /** Con fallback activo, el cierre de la vía UDP ya no es falla del
     * canal: el TCP viviente lo reemplaza (§8.5) sin reintentos nuevos. */
    private fun onUdpClosed() {
        if (fallbackStarted.get()) return
        deliverClosed()
    }

    private fun onTcpClosed() {
        deliverClosed()
    }

    private fun deliverClosed() {
        if (closedDelivered.compareAndSet(false, true)) onEvent(UdpPreferredEvent.Closed)
    }

    private fun runFallbackWatch() {
        try {
            Thread.sleep(TCP_FALLBACK_AFTER_MS)
        } catch (_: InterruptedException) {
            return
        }
        if (udp.receivedAnyDatagram()) {
            // UDP viable: el cliente TCP queda inactivo para esta conexión.
            onEvent(UdpPreferredEvent.TransportActive(isUdp = true))
            return
        }
        // §8.5: sin UDP viable, el TCP toma el consumo de la AU corriente.
        fallbackStarted.set(true)
        udp.close()
        tcp.start()
        onEvent(UdpPreferredEvent.TransportActive(isUdp = false))
    }

    private companion object {
        const val TCP_FALLBACK_AFTER_MS = 900L
    }
}

/** Contrato común de los clientes media (TCP §4.2 y UDP §4.4, CR-062). */
internal interface MediaClient {
    fun start()

    fun close()
}

/**
 * Cliente media UDP (CR-062, SPEC-003 §4.4): un socket por JOIN. El hilo
 * recibe datagramas TWDU, entrega AU autenticadas con latest-frame-wins
 * (el descarte de frames viejos ocurre aquí, sin llegar a la app) y
 * reenvía el keepalive del mismo nonce cada 5 s.
 */
internal class UdpMediaClient(
    private val host: String,
    private val sessionId: ByteArray,
    sessionKey: ByteArray,
    private val port: Int = MediaCrypto.PORT,
    private val onAccessUnit: (H264MediaClient.EncodedAccessUnit) -> Unit,
    private val onClosed: () -> Unit,
) : MediaClient {
    private val codec = UdpMediaCodec(sessionKey)
    private val running = AtomicBoolean(false)
    private val lastSeqAccepted = AtomicLong(-1)

    @Volatile
    private var socket: DatagramSocket? = null

    private var worker: Thread? = null

    /** Para el fallback §8.5: ¿llegó el primer datagrama autenticado? */
    @Volatile
    private var anyAuthenticatedDatagram = false

    fun receivedAnyDatagram(): Boolean = anyAuthenticatedDatagram

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker =
            Thread(
                ::runClient,
                "twinpad-media-udp",
            ).apply {
                start()
            }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        worker?.interrupt()
    }

    private fun runClient() {
        try {
            val datagramSocket = DatagramSocket(null as java.net.SocketAddress?)
            socket = datagramSocket
            datagramSocket.reuseAddress = true
            // CR-065: un IDR de la escena pesada llega como una ráfaga de
            // cientos de datagramas (varios cientos de KB en pocos ms). El
            // búfer por defecto del kernel (rmem_default ≈ 224 KB) desborda
            // en plena ráfaga (RcvbufErrors del kernel subía ~36/s), se
            // pierden fragmentos del AU clave, el reensamblador lo abandona
            // incompleto y el gate del decoder se queda esperando un IDR que
            // ya nunca completa: imagen congelada. Se pide un búfer amplio
            // ANTES de connect(); el kernel lo acota a rmem_max (8 MB en el
            // dispositivo de prueba).
            datagramSocket.soTimeout = RECEIVE_TIMEOUT_MS.toInt()
            runCatching { datagramSocket.receiveBufferSize = RECV_SOCKET_BUFFER_BYTES }
            datagramSocket.connect(InetSocketAddress(host, port))
            val joinNonce = ByteArray(MediaCrypto.NONCE_BYTES).also(SecureRandom()::nextBytes)
            val mediaKey = codec.udpMediaKey(joinNonce)
            val reassembler = UdpReassembler(mediaKey, codec)
            sendJoin(datagramSocket, joinNonce, FIRST_JOIN_LAST_SEQ)
            var nextKeepaliveNanos = System.nanoTime() + KEEPALIVE_PERIOD_NANOS
            val buffer = ByteArray(RECV_BUFFER_BYTES)
            while (running.get()) {
                if (System.nanoTime() >= nextKeepaliveNanos) {
                    val lastSeq = lastSeqAccepted.get().coerceAtLeast(0)
                    sendJoin(datagramSocket, joinNonce, lastSeq)
                    nextKeepaliveNanos = System.nanoTime() + KEEPALIVE_PERIOD_NANOS
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    datagramSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val unit = reassembler.ingestUnit(packet.data, packet.length)
                if (reassembler.latestSeq > lastSeqAccepted.get()) {
                    lastSeqAccepted.set(reassembler.latestSeq)
                }
                if (unit == null) continue
                anyAuthenticatedDatagram = true
                onAccessUnit(unit)
            }
        } catch (_: Exception) {
            // El caller reporta el estado INTERRUPTED/INACTIVE; ningún
            // datagrama no autenticado se entrega jamás (§4.1).
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            onClosed()
        }
    }

    /** JOIN §4.2 (primer envío) o keepalive del mismo nonce (§4.4). */
    private fun sendJoin(
        datagramSocket: DatagramSocket,
        joinNonce: ByteArray,
        lastSeq: Long,
    ) {
        val join = codec.sealJoin(sessionId, lastSeq, joinNonce)
        runCatching { datagramSocket.send(DatagramPacket(join, join.size)) }
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 100L

        /** §4.4: keepalive del MISMO nonce cada 1 000 ms (señal R11). */
        const val KEEPALIVE_PERIOD_NANOS = 1_000L * 1_000_000L

        /** Primer JOIN de la conexión: el server resetea `seq` a 0 (§4.4). */
        const val FIRST_JOIN_LAST_SEQ = 0L

        /** Tope de lectura: recovery FEC máximo 1 448 B (§4.4). */
        const val RECV_BUFFER_BYTES = 1_448

        /** Búfer del socket UDP (CR-065): absorbe la ráfaga de un IDR. */
        const val RECV_SOCKET_BUFFER_BYTES = 4 * 1024 * 1024
    }
}

/** Envelope abierto del datagrama TWDU (§4.3): header autenticado + plaintext. */
internal data class OpenUdpEnvelope(
    val seq: Long,
    val auId: Int,
    val flags: Int,
    val fragIndex: Int,
    val fragTotal: Int,
    val plaintext: ByteArray,
)

/**
 * Codec del canal media UDP (CR-062): misma serialización de bytes que
 * `twinpad_core::media_udp` — JOIN de 92 B (§4.2), envelope TWDU AES-256-
 * GCM con AAD del header de 15 B y recovery FEC XOR por grupos de ≤ 4
 * datagramas (§4.4). `sealDataDatagram`/`sealRecoveryDatagram` solo los
 * usan los tests de TEST-0140: el Servidor es el emisor real.
 */
internal class UdpMediaCodec(private val sessionKey: ByteArray) {
    init {
        require(sessionKey.size == MediaCrypto.SESSION_KEY_BYTES)
    }

    /** `udp_media_key` de §4.4: HKDF-SHA256(prk = HMAC(join_nonce,
     * session_key), key... = HMAC(prk, UDP_KEY_INFO ‖ 0x01)). */
    fun udpMediaKey(joinNonce: ByteArray): ByteArray {
        val prk = hmac(joinNonce, sessionKey)
        return hmac(prk, UDP_KEY_INFO_BYTES, byteArrayOf(HKDF_FIRST_BLOCK.toByte()))
    }

    /** HMAC-SHA256 que liga session_id + last_seq + join_nonce (§4.2). */
    fun joinMac(
        sessionId: ByteArray,
        lastSeq: Long,
        joinNonce: ByteArray,
    ): ByteArray =
        hmac(
            sessionKey,
            JOIN_CONTEXT_BYTES,
            sessionId,
            u32Bytes(lastSeq),
            joinNonce,
        )

    /** JOIN de 92 B (§4.2): `magic ‖ session_id ‖ last_seq ‖ join_nonce ‖
     * HMAC`. */
    fun sealJoin(
        sessionId: ByteArray,
        lastSeq: Long,
        joinNonce: ByteArray,
    ): ByteArray {
        require(
            sessionKey.size == MediaCrypto.SESSION_KEY_BYTES &&
                sessionId.size == MediaCrypto.SESSION_ID_BYTES &&
                joinNonce.size == MediaCrypto.NONCE_BYTES,
        )
        val datagram = ByteArray(UDP_JOIN_BYTES)
        JOIN_MAGIC.copyInto(datagram, 0)
        sessionId.copyInto(datagram, JOIN_MAGIC.size)
        putU32(datagram, JOIN_MAGIC.size + MediaCrypto.SESSION_ID_BYTES, lastSeq)
        joinNonce.copyInto(datagram, JOIN_MAGIC.size + MediaCrypto.SESSION_ID_BYTES + U32_BYTES)
        joinMac(sessionId, lastSeq, joinNonce).copyInto(datagram, UDP_JOIN_BYTES - MAC_32_BYTES)
        return datagram
    }

    /** AES-256-GCM con AAD del header de 15 B (§4.4); nonce `0‖seq_be64`. */
    fun openDatagram(
        mediaKey: ByteArray,
        datagram: ByteArray,
    ): OpenUdpEnvelope {
        require(datagram.size >= DATAGRAM_HEADER_BYTES + MediaCrypto.TAG_BYTES)
        require(
            datagram.size <= maxAcceptedDatagramBytes(datagram),
            { "datagrama UDP fuera del tope del canal (§4.4)" },
        )
        require(datagram.copyOf(DATA_MAGIC.size).contentEquals(DATA_MAGIC), { "magia TWDU inválida" })
        val header = datagram.copyOf(DATAGRAM_HEADER_BYTES)
        val seq = readU32(header, SEQ_OFFSET)
        val cipher = gcmCipher(Cipher.DECRYPT_MODE, mediaKey, seq)
        cipher.updateAAD(header)
        val plaintext =
            cipher.doFinal(
                datagram,
                DATAGRAM_HEADER_BYTES,
                datagram.size - DATAGRAM_HEADER_BYTES,
            )
        return OpenUdpEnvelope(
            seq = seq,
            auId = readU16(header, AU_ID_OFFSET),
            flags = header[FLAGS_OFFSET].toInt() and BYTE_MASK,
            fragIndex = readU16(header, FRAG_INDEX_OFFSET),
            fragTotal = readU16(header, FRAG_TOTAL_OFFSET),
            plaintext = plaintext,
        )
    }

    /** TEST-0140: sellado de un datagrama de datos (lado Servidor).
     *  Detekt: el wire format §4.4 exige los 7 campos por contrato (LongParameterList). */
    @Suppress("LongParameterList")
    fun sealDataDatagram(
        mediaKey: ByteArray,
        seq: Long,
        auId: Int,
        flags: Int,
        fragIndex: Int,
        fragTotal: Int,
        plaintext: ByteArray,
    ): ByteArray {
        require(plaintext.size <= MAX_PAYLOAD_BYTES)
        require(flags and DATA_RESERVED_MASK == 0, { "flags reservados prohibidos" })
        return sealEnvelope(mediaKey, seq, auId, flags, fragIndex, fragTotal, plaintext)
    }

    /** TEST-0140: sellado del envelope recovery FEC (`frag_index = count`). */
    fun sealRecoveryDatagram(
        mediaKey: ByteArray,
        seq: Long,
        count: Int,
        recoveryPlaintext: ByteArray,
    ): ByteArray = sealEnvelope(mediaKey, seq, FEC_AU_ID, FLAG_FEC, count, 0, recoveryPlaintext)

    // Detekt: el wire format §4.4 exige los 7 campos por contrato (LongParameterList).
    @Suppress("LongParameterList")
    private fun sealEnvelope(
        mediaKey: ByteArray,
        seq: Long,
        auId: Int,
        flags: Int,
        fragIndex: Int,
        fragTotal: Int,
        plaintext: ByteArray,
    ): ByteArray {
        val header = buildHeader(seq, auId, flags, fragIndex, fragTotal)
        val cipher = gcmCipher(Cipher.ENCRYPT_MODE, mediaKey, seq)
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    private fun gcmCipher(
        mode: Int,
        mediaKey: ByteArray,
        seq: Long,
    ): Cipher =
        com.twinpad.android.app.wire.CryptoPrimitives.gcmCipher(
            mode,
            mediaKey,
            MediaCrypto.TAG_BYTES * BITS_PER_BYTE,
            udpNonce(seq),
        )

    private fun hmac(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray = com.twinpad.android.app.wire.CryptoPrimitives.hmacSha256(key, *parts)

    companion object {
        internal val JOIN_MAGIC = "TWPUDP01".toByteArray(Charsets.US_ASCII)
        internal val DATA_MAGIC = "TWDU".toByteArray(Charsets.US_ASCII)
        private val JOIN_CONTEXT_BYTES = "twinpad.v1 media udp client".toByteArray(Charsets.US_ASCII)
        private val UDP_KEY_INFO_BYTES =
            "twinpad.v1 media udp server-to-client aes-256-gcm".toByteArray(Charsets.US_ASCII)
        internal const val U32_BYTES = 4
        internal const val U16_BYTES = 2
        internal const val U64_BYTES = 8
        internal const val U32_MASK = 0xffff_ffffL
        internal const val U16_MASK = 0xffff
        internal const val GCM_NONCE_BYTES = 12
        internal const val MAC_32_BYTES = 32
        internal const val UDP_JOIN_BYTES = 92
        internal const val DATAGRAM_HEADER_BYTES = 15
        internal const val SEQ_OFFSET = 4
        internal const val AU_ID_OFFSET = 8
        internal const val FLAGS_OFFSET = 10
        internal const val FRAG_INDEX_OFFSET = 11
        internal const val FRAG_TOTAL_OFFSET = 13
        internal const val MAX_PAYLOAD_BYTES = 1_360
        internal const val MAX_DATAGRAM_BYTES = DATAGRAM_HEADER_BYTES + MAX_PAYLOAD_BYTES + MediaCrypto.TAG_BYTES
        internal const val FEC_GROUP_SIZE = 4
        internal const val FEC_MEMBER_BYTES = 7
        internal const val MAX_MEMBER_BYTES = MAX_PAYLOAD_BYTES + MediaCrypto.TAG_BYTES
        internal const val MAX_RECOVERY_DATAGRAM_BYTES =
            DATAGRAM_HEADER_BYTES + 5 + FEC_GROUP_SIZE * U16_BYTES +
                FEC_GROUP_SIZE * FEC_MEMBER_BYTES + MAX_MEMBER_BYTES + MediaCrypto.TAG_BYTES
        internal const val FEC_AU_ID = 0xFFFF
        internal const val FLAG_FIRST = 0x01
        internal const val FLAG_LAST = 0x02
        internal const val FLAG_FEC = 0x04
        internal const val MAX_PENDING_AUS = 4
        internal const val REPLAY_WINDOW = 256
        internal const val MAX_FRAG_TOTAL = 3_085
        internal const val AU_PLAINTEXT_HEADER_BYTES = 17
        internal const val AU_TIMESTAMP_OFFSET = U64_BYTES
        internal const val AU_IDR_OFFSET = 2 * U64_BYTES
        internal const val RECOVERY_HEADER_BYTES = U32_BYTES + 1
        internal const val RECOVERY_COUNT_OFFSET = U32_BYTES
        internal const val RECOVERY_META_FRAG_INDEX_OFFSET = 3
        internal const val RECOVERY_META_FRAG_TOTAL_OFFSET = 5
        internal const val BYTE_MASK = 0xff
        internal const val DATA_RESERVED_MASK = (FLAG_FIRST or FLAG_LAST).inv() and BYTE_MASK
        private const val BITS_PER_BYTE = 8
        private const val HKDF_FIRST_BLOCK = 1

        internal fun u32Bytes(value: Long): ByteArray =
            ByteBuffer.allocate(U32_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

        internal fun putU32(
            target: ByteArray,
            offset: Int,
            value: Long,
        ) {
            u32Bytes(value).copyInto(target, offset)
        }

        internal fun readU32(
            source: ByteArray,
            offset: Int,
        ): Long = ByteBuffer.wrap(source, offset, U32_BYTES).order(ByteOrder.BIG_ENDIAN).int.toLong() and U32_MASK

        internal fun readU16(
            source: ByteArray,
            offset: Int,
        ): Int = ByteBuffer.wrap(source, offset, U16_BYTES).order(ByteOrder.BIG_ENDIAN).short.toInt() and U16_MASK

        internal fun u16Bytes(value: Int): ByteArray =
            ByteBuffer.allocate(U16_BYTES).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

        /** Nonce GCM: `0x00000000 ‖ seq_be64` (§4.4). El `putLong` escribe
         *  en el índice 4: los primeros 4 bytes quedan en cero y la seq
         *  ocupa los 8 finales (identico al Core Rust, única forma de
         *  comprender un datagrama servido por el relay real). */
        internal fun udpNonce(seq: Long): ByteArray {
            return ByteBuffer.allocate(GCM_NONCE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(GCM_NONCE_BYTES - U64_BYTES, seq)
                .array()
        }

        internal fun buildHeader(
            seq: Long,
            auId: Int,
            flags: Int,
            fragIndex: Int,
            fragTotal: Int,
        ): ByteArray {
            val header = ByteArray(DATAGRAM_HEADER_BYTES)
            DATA_MAGIC.copyInto(header, 0)
            putU32(header, SEQ_OFFSET, seq)
            u16Bytes(auId).copyInto(header, AU_ID_OFFSET)
            header[FLAGS_OFFSET] = flags.toByte()
            u16Bytes(fragIndex).copyInto(header, FRAG_INDEX_OFFSET)
            u16Bytes(fragTotal).copyInto(header, FRAG_TOTAL_OFFSET)
            return header
        }

        /** El recovery FEC (au_id = 0xFFFF, flags FEC) admite 1 448 B. */
        private fun maxAcceptedDatagramBytes(datagram: ByteArray): Int {
            val isRecovery =
                isRecoveryHeader(datagram)
            return if (isRecovery) MAX_RECOVERY_DATAGRAM_BYTES else MAX_DATAGRAM_BYTES
        }

        private fun isRecoveryHeader(datagram: ByteArray): Boolean {
            if (datagram.size < DATAGRAM_HEADER_BYTES + MediaCrypto.TAG_BYTES) return false
            return readU16(datagram, AU_ID_OFFSET) == FEC_AU_ID &&
                datagram[FLAGS_OFFSET].toInt() and BYTE_MASK == FLAG_FEC
        }
    }
}

/**
 * Reensamblador del cliente UDP (CR-062): ventana anti-replay de 256,
 * pendientes por `au_id` con tope de 4 AUs y abandono con `first_seq`
 * ≥ 256 `seq` atrás, recovery FEC XOR cuando EXACTAMENTE un miembro del
 * grupo falta y entrega latest-frame-wins (fn/ts estrictamente
 * crecientes).
 */

internal class UdpReassembler(
    private val mediaKey: ByteArray,
    private val codec: UdpMediaCodec,
) {
    private val seen = TreeSet<Long>()
    private var watermark = 0L
    private val rawBySeq = TreeMap<Long, ByteArray>()
    private val pending = TreeMap<Int, PendingUdpAu>()
    private var delivered: Pair<Long, Long>? = null

    /** Último `seq` autenticado aceptado; señal `last_seq` (R11, §4.4). */
    var latestSeq: Long = -1
        private set

    /** Null ante replay/framing inválido o descarte (el canal NO cierra). */
    fun ingestUnit(
        buffer: ByteArray,
        length: Int,
    ): H264MediaClient.EncodedAccessUnit? {
        val datagram = buffer.copyOf(length)
        val envelope =
            try {
                codec.openDatagram(mediaKey, datagram)
            } catch (_: Exception) {
                return null // no autenticado: nunca entrega (§4.1)
            }
        if (!inWindow(envelope.seq) || !seen.add(envelope.seq)) return null
        latestSeq = maxOf(latestSeq, envelope.seq)
        val assembled =
            when {
                envelope.auId == UdpMediaCodec.FEC_AU_ID -> ingestRecovery(envelope)
                envelope.flags and UdpMediaCodec.DATA_RESERVED_MASK != 0 -> null
                else -> acceptData(envelope)
            }
        rawBySeq[envelope.seq] = datagram
        if (envelope.seq > watermark) {
            watermark = envelope.seq
            pruneWindow()
        }
        purgeAbandoned(envelope.seq)
        return assembled?.let(::encode)
    }

    private fun inWindow(seq: Long): Boolean {
        return seq >= watermark || watermark - seq < UdpMediaCodec.REPLAY_WINDOW.toLong()
    }

    private fun acceptData(envelope: OpenUdpEnvelope): ByteArray? {
        val assembled = enqueueFragment(envelope) ?: return null
        return validateAndYield(assembled)
    }

    private fun enqueueFragment(envelope: OpenUdpEnvelope): ByteArray? {
        val fragTotal = envelope.fragTotal
        if (
            fragTotal == 0 ||
            fragTotal > UdpMediaCodec.MAX_FRAG_TOTAL ||
            envelope.fragIndex >= fragTotal
        ) {
            return null
        }
        if (pending.size >= UdpMediaCodec.MAX_PENDING_AUS && !pending.containsKey(envelope.auId)) {
            pending.remove(pending.firstKey())
        }
        val pendingAu =
            pending.getOrPut(envelope.auId) {
                PendingUdpAu(firstSeq = envelope.seq, fragTotal = fragTotal)
            }
        if (pendingAu.fragTotal != fragTotal) {
            pendingAu.fragTotal = fragTotal
            pendingAu.fragments.tailMap(fragTotal).clear()
        }
        if (!pendingAu.fragments.containsKey(envelope.fragIndex)) {
            pendingAu.fragments[envelope.fragIndex] = envelope.plaintext
        }
        if (pendingAu.fragments.size != fragTotal) return null
        pending.remove(envelope.auId)
        return pendingAu.assemble()
    }

    /** Recovery del grupo (§4.4): EXACTAMENTE un miembro faltante se
     * reconstruye del XOR y su AU completa se entrega; con 0 o ≥ 2
     * faltantes no hay entrega. Detekt: la validación del grupo exige
     * cortes tempranos por contrato (ReturnCount). */
    @Suppress("ReturnCount")
    private fun ingestRecovery(envelope: OpenUdpEnvelope): ByteArray? {
        if (envelope.flags != UdpMediaCodec.FLAG_FEC || envelope.fragTotal != 0) return null
        val parsed = RecoveryPlain.parse(envelope.plaintext) ?: return null
        val members: List<Long> = (0L until parsed.count.toLong()).map { parsed.groupBase + it }
        val missing = members.filterNot { rawBySeq.containsKey(it) }
        if (missing.size != 1) return null
        val missingSeq = missing.single()
        val missingIndex = (missingSeq - parsed.groupBase).toInt()
        val restored = parsed.reconstruct(missingIndex, rawBySeq) ?: return null
        if (seen.contains(missingSeq)) return null
        val meta = parsed.metas[missingIndex]
        val restoredDatagram =
            UdpMediaCodec.buildHeader(
                missingSeq,
                meta.auId,
                meta.flags,
                meta.fragIndex,
                meta.fragTotal,
            ) + restored
        val restoredEnvelope =
            try {
                codec.openDatagram(mediaKey, restoredDatagram)
            } catch (_: Exception) {
                return null
            }
        if (restoredEnvelope.flags and UdpMediaCodec.DATA_RESERVED_MASK != 0) return null
        seen.add(restoredEnvelope.seq)
        rawBySeq[restoredEnvelope.seq] = restoredDatagram
        return validateAndYield(enqueueFragment(restoredEnvelope) ?: return null)
    }

    /** La entrega respeta latest-frame-wins de §4.3 (fn/ts estrictos). */
    private fun validateAndYield(assembled: ByteArray): ByteArray? {
        if (assembled.size < UdpMediaCodec.AU_PLAINTEXT_HEADER_BYTES) return null
        val frameNumber = readBeLong(assembled, 0)
        val timestampUs = readBeLong(assembled, UdpMediaCodec.AU_TIMESTAMP_OFFSET)
        val previous = delivered
        if (previous != null && (frameNumber <= previous.first || timestampUs <= previous.second)) {
            return null
        }
        delivered = frameNumber to timestampUs
        return assembled
    }

    private fun encode(assembled: ByteArray): H264MediaClient.EncodedAccessUnit =
        H264MediaClient.EncodedAccessUnit(
            frameNumber = readBeLong(assembled, 0),
            timestampUs = readBeLong(assembled, UdpMediaCodec.AU_TIMESTAMP_OFFSET),
            idr = assembled[UdpMediaCodec.AU_IDR_OFFSET].toInt() == 1,
            bytes = assembled.copyOfRange(UdpMediaCodec.AU_PLAINTEXT_HEADER_BYTES, assembled.size),
        )

    private fun pruneWindow() {
        if (watermark < UdpMediaCodec.REPLAY_WINDOW.toLong()) return
        val cutoff = watermark - UdpMediaCodec.REPLAY_WINDOW.toLong()
        seen.headSet(cutoff, false).clear()
        rawBySeq.headMap(cutoff, false).clear()
    }

    private fun purgeAbandoned(seq: Long): Int {
        if (seq < UdpMediaCodec.REPLAY_WINDOW.toLong()) return 0
        val cutoff = seq - UdpMediaCodec.REPLAY_WINDOW.toLong()
        var removed = 0
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().firstSeq <= cutoff) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun readBeLong(
        source: ByteArray,
        offset: Int,
    ): Long = ByteBuffer.wrap(source, offset, UdpMediaCodec.U64_BYTES).order(ByteOrder.BIG_ENDIAN).long
}

/** AU pendiente de la ventana de reconstrucción (§4.3). */
private class PendingUdpAu(
    val firstSeq: Long,
    var fragTotal: Int,
) {
    val fragments = TreeMap<Int, ByteArray>()

    fun assemble(): ByteArray {
        var total = 0
        for (index in 0 until fragTotal) {
            val fragment = fragments[index] ?: return ByteArray(0)
            total += fragment.size
        }
        val assembled = ByteArray(total)
        var offset = 0
        for (index in 0 until fragTotal) {
            val fragment = fragments[index] ?: return ByteArray(0)
            fragment.copyInto(assembled, offset)
            offset += fragment.size
        }
        return assembled
    }
}

/**
 * Plaintext del envelope FEC (§4.4): `group_base ‖ count ‖ len_i ‖
 * metas_i ‖ XOR( ct+tag )`, con `len_i` u16 y
 * `meta_i` = `au_id ‖ flags ‖ frag_index ‖ frag_total` (7 B).
 */
internal data class RecoveryPlain(
    val groupBase: Long,
    val count: Int,
    val lens: IntArray,
    val metas: List<RecoveryMemberMeta>,
    val xor: ByteArray,
) {
    /** XOR del recovery contra los miembros presentes; null si falta otro. */
    fun reconstruct(
        missingIndex: Int,
        rawBySeq: Map<Long, ByteArray>,
    ): ByteArray? {
        if (xor.size < lens[missingIndex]) return null
        val restored = xor.copyOf(lens[missingIndex])
        for (index in 0 until count) {
            if (index == missingIndex) continue
            val member = rawBySeq[groupBase + index] ?: return null
            val ciphertext = member.copyOfRange(UdpMediaCodec.DATAGRAM_HEADER_BYTES, member.size)
            for (slot in 0 until minOf(restored.size, ciphertext.size)) {
                restored[slot] = (restored[slot].toInt() xor ciphertext[slot].toInt()).toByte()
            }
        }
        return restored
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecoveryPlain) return false
        return groupBase == other.groupBase &&
            count == other.count &&
            lens.contentEquals(other.lens) &&
            metas == other.metas &&
            xor.contentEquals(other.xor)
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * groupBase.hashCode() + count) + lens.contentHashCode()) + metas.hashCode()) +
            xor.contentHashCode()

    companion object {
        /** Detekt: la gramática del recovery §4.4 exige validar campo a
         * campo con cortes tempranos (ReturnCount). */
        @Suppress("ReturnCount")
        fun parse(plaintext: ByteArray): RecoveryPlain? {
            if (plaintext.size < UdpMediaCodec.RECOVERY_HEADER_BYTES) return null
            val groupBase = UdpMediaCodec.readU32(plaintext, 0)
            val count = plaintext[UdpMediaCodec.RECOVERY_COUNT_OFFSET].toInt() and UdpMediaCodec.BYTE_MASK
            if (count == 0 || count > UdpMediaCodec.FEC_GROUP_SIZE) return null
            val lens = IntArray(count)
            for (index in 0 until count) {
                val len =
                    UdpMediaCodec.readU16(
                        plaintext,
                        UdpMediaCodec.RECOVERY_HEADER_BYTES + index * UdpMediaCodec.U16_BYTES,
                    )
                if (len > UdpMediaCodec.MAX_MEMBER_BYTES) return null
                lens[index] = len
            }
            val metasStart = UdpMediaCodec.RECOVERY_HEADER_BYTES + count * UdpMediaCodec.U16_BYTES
            val metasEnd = metasStart + count * UdpMediaCodec.FEC_MEMBER_BYTES
            if (plaintext.size < metasEnd) return null
            val metas = mutableListOf<RecoveryMemberMeta>()
            for (index in 0 until count) {
                val from = metasStart + index * UdpMediaCodec.FEC_MEMBER_BYTES
                val chunk = plaintext.copyOfRange(from, from + UdpMediaCodec.FEC_MEMBER_BYTES)
                metas +=
                    RecoveryMemberMeta(
                        auId = UdpMediaCodec.readU16(chunk, 0),
                        flags = chunk[UdpMediaCodec.U16_BYTES].toInt() and UdpMediaCodec.BYTE_MASK,
                        fragIndex = UdpMediaCodec.readU16(chunk, UdpMediaCodec.RECOVERY_META_FRAG_INDEX_OFFSET),
                        fragTotal = UdpMediaCodec.readU16(chunk, UdpMediaCodec.RECOVERY_META_FRAG_TOTAL_OFFSET),
                    )
            }
            val xor = plaintext.copyOfRange(metasEnd, plaintext.size)
            if (xor.size != (lens.maxOrNull() ?: 0)) return null
            return RecoveryPlain(groupBase, count, lens, metas, xor)
        }
    }
}

/** Meta de un miembro del grupo FEC dentro del plaintext de recovery. */
internal data class RecoveryMemberMeta(
    val auId: Int,
    val flags: Int,
    val fragIndex: Int,
    val fragTotal: Int,
)
