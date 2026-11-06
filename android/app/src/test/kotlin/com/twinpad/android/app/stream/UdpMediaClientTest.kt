package com.twinpad.android.app.stream

import org.junit.Assert.assertArrayEquals
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

    // TEST-0140 — CR-062 (SPEC-003 §4.4): el nonce GCM vale `0x00000000 ‖
    // seq_be64`; el teléfono y el Core derivan la MISMA clave con este
    // layout (un nonce invertido deja todas las AU sin autenticar y
    // fuerza el fallback TCP del §8.5 en cada conexión).
    @Test
    fun nonce_gcm_layout_cero_prefijo_seq_sufijo() {
        val nonce = UdpMediaCodec.udpNonce(0x0102030405L)
        assertEquals(UdpMediaCodec.GCM_NONCE_BYTES, nonce.size)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), nonce.copyOfRange(0, 4).map { it.toInt() }.toIntArray())
        assertEquals(
            0x0102030405L,
            ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.BIG_ENDIAN).long,
        )
    }

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

    // TEST-0162 — CR-081 (SPEC-003 §8.5): la señal del watchdog
    // `runFallbackWatch` debe avanzar con CUALQUIER datagrama autenticado,
    // no solo con AU completa: una IDR fragmentada con ≥ 2 pérdidas por
    // grupo FEC no ensambla, pero el canal está vivo (`latest_seq` avanza
    // con bytes autenticados sanos) y el UDP no debe ser condenado.
    @Test
    fun watchdog_signal_avanza_con_fragmento_autenticado_sin_au() {
        val codec = UdpMediaCodec(sessionKey)
        val key = mediaKey(ByteArray(32) { 0x31 })
        val reassembler = UdpReassembler(key, codec)
        // Fragmento 0 de una AU de 10 (el resto se pierde y la recovery
        // es imposible con > 1 faltantes): autenticado, sin AU completa.
        val datagram =
            codec.sealDataDatagram(
                key,
                seq = 0,
                auId = 1,
                flags = UdpMediaCodec.FLAG_FIRST,
                fragIndex = 0,
                fragTotal = 10,
                plaintext = ByteArray(8) { 0x0A },
            )
        assertNull(reassembler.ingestUnit(datagram, datagram.size))
        // Señal del watchdog (CR-081): `latest_seq` avanza aunque nada
        // ensamble — `receivedAnyAuthenticatedFragment()` se enciende y
        // el fallback TCP no roba la conexión.
        assertEquals(0L, reassembler.latestSeq)
        val next =
            codec.sealDataDatagram(
                key,
                seq = 5,
                auId = 2,
                flags = 0,
                fragIndex = 0,
                fragTotal = 10,
                plaintext = ByteArray(8) { 0x0B },
            )
        assertNull(reassembler.ingestUnit(next, next.size))
        assertEquals(5L, reassembler.latestSeq)
    }

    // TEST-0165 — CR-082 (SPEC-003 §4.4): la política del watch de
    // recuperación re-JOINa tras 5 000 ms sin progreso de seq y entrega
    // Close tras 2 intentos sin revivir; el progreso re-arma y resetea
    // los intentos; sin progreso JAMÁS el watch no actúa (el arranque
    // frío lo cubre el one-shot §8.5 de 900 ms, TEST-0162/TEST-0140).
    @Test
    fun watch_recuperacion_rejoin_tras_idle_y_close_tras_dos_intentos() {
        val policy =
            MediaRecoveryPolicy(
                idleTriggerMs = 5_000L,
                maxRejoinAttempts = 2,
            )
        // Señal de AU entregada pasiva (-1: como en CR-082, el starve no
        // entra en este banco y no perturba el camino del re-JOIN).
        val sinAu = -1L
        // Sin progreso jamás: el watch no actúa.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(-1L, sinAu, 0L))
        // Primer progreso: arma con ventana limpia.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(0L, sinAu, 1_000L))
        // Idle < ventana: sin acción.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(0L, sinAu, 5_999L))
        // Idle ≥ 5 000 ms: re-JOIN (1.er intento).
        assertEquals(MediaRecoveryPolicy.Action.Rejoin, policy.tick(0L, sinAu, 6_000L))
        // Progreso tras el re-JOIN: resetea intentos y re-arma.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(42L, sinAu, 6_500L))
        // Idle completa sin progreso: 2.º re-JOIN.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(42L, sinAu, 11_499L))
        assertEquals(MediaRecoveryPolicy.Action.Rejoin, policy.tick(42L, sinAu, 11_500L))
        // Seguir sin progreso: el 2.º intento se agota y el 3.er idle
        // destruye el canal (Closed al caller del compuesto).
        assertEquals(MediaRecoveryPolicy.Action.Rejoin, policy.tick(42L, sinAu, 16_500L))
        assertEquals(MediaRecoveryPolicy.Action.Close, policy.tick(42L, sinAu, 21_500L))
    }

    // TEST-0169 — CR-083 (pieza C): starve de AU con canal auténtico vivo
    // (seq progresando, ninguna AU entregada) — la renegociación decreta
    // una vez por minuto (RENEGOTIATE_GAP_MS) y tras RENEGOTIATE_MAX
    // renegociaciones entrega Close; con AU fluente reinicia el starve y
    // resetea los contadores (sin renegociación alguna).
    @Test
    fun starvation_renegocia_una_vez_por_minuto_y_cierra_tras_tres() {
        val policy =
            MediaRecoveryPolicy(
                idleTriggerMs = 5_000L,
                maxRejoinAttempts = 2,
                starveTriggerMs = 8_000L,
                maxRenegotiations = 3,
            )
        // AU entregada al arranque: re-arma el starve.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(0L, 10L, 1_000L))
        // Seq progresando (canal auténtico vivo) pero AU congelada: sin
        // starve aún (< AU_STARVE_MS) y gap intacto: sin acción.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(30L, 10L, 2_000L))
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(60L, 10L, 8_999L))
        // Starve en 8 000 ms exactos: la 1.ª renegociación.
        assertEquals(MediaRecoveryPolicy.Action.Renegotiate, policy.tick(90L, 10L, 9_000L))
        // Gap de 1 min no cumplido: sin nueva renegociación aunque starve.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(120L, 10L, 30_000L))
        // Gap cumplido: 2.ª renegociación.
        assertEquals(MediaRecoveryPolicy.Action.Renegotiate, policy.tick(150L, 10L, 69_000L))
        // 3.ª renegociación (gap 1 min desde la 2.ª).
        assertEquals(MediaRecoveryPolicy.Action.Renegotiate, policy.tick(180L, 10L, 129_000L))
        // Starve persistente más allá del tope: Close.
        assertEquals(MediaRecoveryPolicy.Action.Close, policy.tick(210L, 10L, 189_000L))
        // AU fluente de nuevo: resetea starve y contadores (el canal resucita).
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(240L, 20L, 190_000L))
        // Y al volver a morir el flujo de AU, la cuenta reinicia: 1.ª otra vez.
        assertEquals(MediaRecoveryPolicy.Action.None, policy.tick(270L, 20L, 197_999L))
        assertEquals(MediaRecoveryPolicy.Action.Renegotiate, policy.tick(300L, 20L, 198_000L))
    }

    // TEST-0170 — CR-084 (SPEC-003 §4.4): una AU de más de
    // REPLAY_WINDOW (256) fragmentos ensambla y se entrega al llegar el
    // último — la purga abandona pendientes solo cuando su ÚLTIMO seq
    // esperado venció (`firstSeq + fragTotal - 1`), no al cruzar la
    // ventana media (la regla previa mataba la AU en el fragmento 257.º:
    // la IDR de 1080p, 485-588 dg, jamás ensamblaba — dictamen del banco
    // del 17:31: Qin=0 con fragmentos auténticos fluidos).
    @Test
    fun au_sobre_ventana_ensambla_completa() {
        val codec = UdpMediaCodec(sessionKey)
        val key = mediaKey(ByteArray(32) { 0x44 })
        val reassembler = UdpReassembler(key, codec)
        val bodySize = 500 * 100 - UdpMediaCodec.AU_PLAINTEXT_HEADER_BYTES
        val body = ByteArray(bodySize) { index -> (index % 251).toByte() }
        val plain =
            ByteBuffer
                .allocate(UdpMediaCodec.AU_PLAINTEXT_HEADER_BYTES + body.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(7_001L)
                .putLong(5_000L)
                .put(1.toByte())
                .put(body)
                .array()
        // 500 fragmentos exactos de 100 B (por encima de la ventana).
        val chunks = plain.toList().chunked(100)
        assertEquals(500, chunks.size)
        var entregadas = 0
        var entregada: H264MediaClient.EncodedAccessUnit? = null
        chunks.forEachIndexed { index, chunk ->
            var flags = 0
            if (index == 0) flags = flags or UdpMediaCodec.FLAG_FIRST
            if (index == chunks.size - 1) flags = flags or UdpMediaCodec.FLAG_LAST
            val datagram =
                codec.sealDataDatagram(
                    key,
                    index.toLong(),
                    1,
                    flags,
                    index,
                    chunks.size,
                    chunk.toByteArray(),
                )
            val unit = reassembler.ingestUnit(datagram, datagram.size)
            if (unit != null) {
                entregadas++
                entregada = unit
            }
        }
        assertEquals(1, entregadas)
        val dejada = requireNotNull(entregada)
        assertEquals(7_001L, dejada.frameNumber)
        assertEquals(5_000L, dejada.timestampUs)
        assertTrue(dejada.idr)
        assertEquals(bodySize, dejada.bytes.size)
        // El restante (AU chica siguiente) vive en el test 0162: la grande
        // ya entregada no deja estado sucio (fn/ts de latest-wins vivos).
    }
}
