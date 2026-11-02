package com.twinpad.android.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** TEST-0140 — verifies SPEC-003 §4.4 (CR-062): JOIN de 92 B, envelope
 * TWDU con AAD ligado al header, recovery FEC XOR con EXACTAMENTE un
 * faltante y entrega latest-frame-wins en el lado Android. */
class UdpMediaClientTest {
    private val sessionKey = ByteArray(32) { 0x41 }
    private val sessionId = ByteArray(16) { 0x23 }

    private fun mediaKey(joinNonce: ByteArray): ByteArray = UdpMediaCodec(sessionKey).udpMediaKey(joinNonce)

    // TEST-0140 — CR-062 (SPEC-003 §4.4): JOIN de 92 B con la magia
    // exacta y HMAC que liga session_id + last_seq + join_nonce.
    @Test
    fun join_empaqueta_92_bytes_y_liga_campos() {
        val codec = UdpMediaCodec(sessionKey)
        val nonce = ByteArray(32) { 0x22 }
        val join = codec.sealJoin(sessionId, 42, nonce)
        assertEquals(UdpMediaCodec.UDP_JOIN_BYTES, join.size)
        assertEquals(UdpMediaCodec.JOIN_MAGIC.toList(), join.copyOfRange(0, 8).toList())
        assertEquals(sessionId.toList(), join.sliceArray(8 until 24).toList())
        // last_seq = 42 en el offset 24 (§4.2).
        assertEquals(42L, UdpMediaCodec.readU32(join, 24))
        // Cambiar cualquier campo liga un MAC distinto.
        assertEquals(codec.joinMac(sessionId, 42, nonce).toList(), join.sliceArray(60 until 92).toList())
        val tamperedSeq = codec.joinMac(sessionId, 43, nonce)
        val tamperedSession = codec.joinMac(sessionId.copyOf().also { it[3] = 0x71 }, 42, nonce)
        assertFalse(tamperedSeq.contentEquals(codec.joinMac(sessionId, 42, nonce)))
        assertFalse(tamperedSession.contentEquals(codec.joinMac(sessionId, 42, nonce)))
    }

    // TEST-0140 — CR-062 (SPEC-003 §4.4): roundtrip del envelope TWDU con
    // AAD ligado al header; el tamp del header rompe la autenticidad.
    @Test
    fun envelope_datos_roundtrip_y_aad_ligado() {
        val codec = UdpMediaCodec(sessionKey)
        val key = mediaKey(ByteArray(32) { 0x77 })
        val datagram =
            codec.sealDataDatagram(
                mediaKey = key,
                seq = 9,
                auId = 7,
                flags = UdpMediaCodec.FLAG_FIRST or UdpMediaCodec.FLAG_LAST,
                fragIndex = 0,
                fragTotal = 1,
                plaintext = "twdupayload".toByteArray(),
            )
        assertTrue(datagram.size <= UdpMediaCodec.MAX_DATAGRAM_BYTES)
        val envelope = codec.openDatagram(key, datagram)
        assertEquals(9L, envelope.seq)
        assertEquals(7, envelope.auId)
        assertEquals(0, envelope.fragIndex)
        assertEquals(1, envelope.fragTotal)
        assertEquals("twdupayload".toByteArray().toList(), envelope.plaintext.toList())
        // Tamper del AAD (au_id): GCM falla.
        val tamperedHeader = datagram.copyOf().also { it[UdpMediaCodec.AU_ID_OFFSET] = 0x7A.toByte() }
        runCatching { codec.openDatagram(key, tamperedHeader) }.onSuccess { error("AAD no ligado") }
        // Tamper del ciphertext: GCM falla.
        val tamperedBody = datagram.copyOf().also { it[UdpMediaCodec.DATAGRAM_HEADER_BYTES] = 0x00 }
        runCatching { codec.openDatagram(key, tamperedBody) }.onSuccess { error("tag no verificado") }
    }

    // TEST-0140 — CR-062 (SPEC-003 §4.4): recovery FEC del grupo con
    // EXACTAMENTE un miembro faltante reconstruye la AU completa.
    @Test
    fun recovery_fec_reconstruye_unico_faltante() {
        val codec = UdpMediaCodec(sessionKey)
        val key = mediaKey(ByteArray(32) { 0x88.toByte() })
        val payload = ByteArray(1_500) { index -> (index % 251).toByte() }
        val (datagrams, recovery) =
            emitTestAu(
                codec,
                key,
                frameNumber = 1,
                timestampUs = 100,
                idr = true,
                payload = payload,
                auId = 7,
                startSeq = 0,
            )
        assertEquals(2, datagrams.size) // 2 fragmentos; el recovery viene aparte
        val reassembler = UdpReassembler(key, codec)
        // Primer fragmento: pendiente, sin entrega.
        assertNull(reassembler.ingestUnit(datagrams[0], datagrams[0].size))
        // Faltante del grupo (fragmento 1): entregado vía recovery.
        val unit = reassembler.ingestUnit(recovery, recovery.size)
        assertNotNull(unit)
        assertEquals(1L, unit!!.frameNumber)
        assertEquals(100L, unit.timestampUs)
        assertTrue(unit.idr)
        assertEquals(payload.toList(), unit.bytes.toList())
    }

    // TEST-0140 — CR-062 (SPEC-003 §4.4): la entrega respeta
    // latest-frame-wins; replay y AU con fn/ts no crecientes se descartan.
    @Test
    fun latest_frame_wins_descarta_replays_y_stale() {
        val codec = UdpMediaCodec(sessionKey)
        val key = mediaKey(ByteArray(32) { 0x99.toByte() })
        val reassembler = UdpReassembler(key, codec)
        val payload = ByteArray(1_500) { index -> (index % 151).toByte() }
        val (first, firstRecovery) =
            emitTestAu(
                codec,
                key,
                frameNumber = 5,
                timestampUs = 100,
                idr = true,
                payload = payload,
                auId = 1,
                startSeq = 0,
            )
        // AU completa en el segundo fragmento (sin recovery).
        assertNull(reassembler.ingestUnit(first[0], first[0].size))
        val delivered = reassembler.ingestUnit(first[1], first[1].size)
        assertNotNull(delivered)
        assertEquals(5L, delivered!!.frameNumber)
        assertEquals(1_500, delivered.bytes.size)
        // Recovery con 0 faltantes: sin entrega.
        assertNull(reassembler.ingestUnit(firstRecovery, firstRecovery.size))
        // Replay del mismo datagrama: descarte silencioso.
        assertNull(reassembler.ingestUnit(first[1], first[1].size))
        // AU con fn/ts no crecientes: descartada (latest-frame-wins).
        val staleAu =
            emitTestAu(
                codec,
                key,
                frameNumber = 3,
                timestampUs = 50,
                idr = false,
                payload = payload,
                auId = 2,
                startSeq = 10,
            )
        val stale = staleAu.first
        assertNull(reassembler.ingestUnit(stale[0], stale[0].size))
        assertNull(reassembler.ingestUnit(stale[1], stale[1].size))
    }

    /** TEST-0140: emisor mínimo del lado Servidor para fixture (2 frag + fec).
     *  Detekt: fixture de contrato §4.4 con los 8 campos del wire format (LongParameterList). */
    @Suppress("LongParameterList")
    private fun emitTestAu(
        codec: UdpMediaCodec,
        key: ByteArray,
        frameNumber: Long,
        timestampUs: Long,
        idr: Boolean,
        payload: ByteArray,
        auId: Int,
        startSeq: Long,
    ): Pair<List<ByteArray>, ByteArray> {
        // Plaintext §4.3: fn ‖ ts ‖ flag idr ‖ annex_b.
        val plain =
            ByteBuffer
                .allocate(UdpMediaCodec.AU_PLAINTEXT_HEADER_BYTES + payload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(frameNumber)
                .putLong(timestampUs)
                .put(if (idr) 1.toByte() else 0.toByte())
                .put(payload)
                .array()
        // FragmentACIÓN §4.3: primer bloque al payload máximo + resto.
        val chunks =
            listOf(
                plain.copyOfRange(0, UdpMediaCodec.MAX_PAYLOAD_BYTES),
                plain.copyOfRange(UdpMediaCodec.MAX_PAYLOAD_BYTES, plain.size),
            )
        assertTrue(chunks.size == 2)
        val fragments = mutableListOf<ByteArray>()
        val ciphertexts = mutableListOf<ByteArray>()
        chunks.forEachIndexed { index, chunk ->
            var flags = 0
            if (index == 0) flags = flags or UdpMediaCodec.FLAG_FIRST
            if (index + 1 == chunks.size) flags = flags or UdpMediaCodec.FLAG_LAST
            val datagram =
                codec.sealDataDatagram(key, startSeq + index, auId, flags, index, chunks.size, chunk)
            fragments += datagram
            ciphertexts += datagram.copyOfRange(UdpMediaCodec.DATAGRAM_HEADER_BYTES, datagram.size)
        }
        // Recovery del grupo de 2 en el seq siguiente (§4.4):
        // group_base ‖ count ‖ len_i ‖ metas_i ‖ XOR( ct+tag ).
        val maxLen = ciphertexts.maxOf { it.size }
        val xor =
            ByteArray(maxLen) { slot ->
                ciphertexts.fold(0) { acc, ct -> acc xor if (slot < ct.size) ct[slot].toInt() else 0 }.toByte()
            }
        val recoveryPlain =
            ByteArray(5 + chunks.size * UdpMediaCodec.U16_BYTES + chunks.size * UdpMediaCodec.FEC_MEMBER_BYTES + maxLen)
        UdpMediaCodec.putU32(recoveryPlain, 0, startSeq)
        recoveryPlain[4] = chunks.size.toByte()
        var offset = 5
        ciphertexts.forEach { ct ->
            UdpMediaCodec.u16Bytes(ct.size).copyInto(recoveryPlain, offset)
            offset += UdpMediaCodec.U16_BYTES
        }
        chunks.forEachIndexed { index, _ ->
            var flags = 0
            if (index == 0) flags = flags or UdpMediaCodec.FLAG_FIRST
            if (index + 1 == chunks.size) flags = flags or UdpMediaCodec.FLAG_LAST
            UdpMediaCodec.u16Bytes(auId).copyInto(recoveryPlain, offset)
            recoveryPlain[offset + 2] = flags.toByte()
            UdpMediaCodec.u16Bytes(index).copyInto(recoveryPlain, offset + 3)
            UdpMediaCodec.u16Bytes(chunks.size).copyInto(recoveryPlain, offset + 5)
            offset += UdpMediaCodec.FEC_MEMBER_BYTES
        }
        xor.copyInto(recoveryPlain, offset)
        val recovery = codec.sealRecoveryDatagram(key, startSeq + chunks.size, chunks.size, recoveryPlain)
        return fragments to recovery
    }
}
