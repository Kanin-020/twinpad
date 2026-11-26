package com.twinpad.android.app.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** TEST-0222 — verifies SPEC-006 (CR-126): `amiibo_tag` carries a 540 B
 *  NTAG215 dump and a 14-hex UID; the removal line carries neither field. */
class AmiiboTagCodecTest {
    @Test
    fun present_encodes_uid_and_full_ntag215_dump() {
        val dump = ByteArray(AmiiboTagCodec.DUMP_BYTES) { (it % 251).toByte() }
        val line = AmiiboTagCodec.present("0415aabbccddee", dump)

        assertTrue(line.startsWith("{\"type\":\"amiibo_tag\",\"present\":true,"))
        assertTrue(line.contains("\"uid\":\"0415aabbccddee\""))
        val data = line.substringAfter("\"data\":\"").substringBefore("\"}")
        assertTrue(!data.contains("="))
        assertEquals(AmiiboTagCodec.DUMP_BYTES, Base64.getUrlDecoder().decode(data).size)
        assertArrayEquals(dump, Base64.getUrlDecoder().decode(data))
    }

    @Test
    fun removal_has_no_uid_or_data() {
        assertEquals("{\"type\":\"amiibo_tag\",\"present\":false}", AmiiboTagCodec.REMOVED_LINE)
    }

    @Test
    fun truncate_dump_keeps_the_135_real_pages() {
        // Recorte de un volcado mayor conserva exactamente las páginas 0–134.
        val raw = ByteArray(AmiiboTagCodec.DUMP_BYTES + 4) { it.toByte() }
        val dump = AmiiboTagCodec.truncateDump(raw)
        assertEquals(AmiiboTagCodec.DUMP_BYTES, dump.size)
        assertArrayEquals(raw.copyOf(AmiiboTagCodec.DUMP_BYTES), dump)
    }

    @Test(expected = IllegalArgumentException::class)
    fun present_rejects_uid_outside_the_contract() {
        AmiiboTagCodec.present("0415AABBCCDDEE", ByteArray(AmiiboTagCodec.DUMP_BYTES))
    }

    @Test(expected = IllegalArgumentException::class)
    fun present_rejects_short_dump() {
        AmiiboTagCodec.present(
            "0415aabbccddee",
            ByteArray(AmiiboTagCodec.DUMP_BYTES - 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncate_rejects_incomplete_dump() {
        AmiiboTagCodec.truncateDump(ByteArray(AmiiboTagCodec.DUMP_BYTES - 1))
    }
}
