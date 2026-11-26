package com.twinpad.android.app.wire

import java.util.Base64

/**
 * Codificador de `amiibo_tag` (SPEC-006 §4, CR-126). El teléfono solo lee el
 * Amiibo: envía el volcado NTAG215 completo (páginas 0–134, 540 B) y su UID
 * de 7 bytes en hex minúscula. Cemu consume los primeros 532 B.
 */
object AmiiboTagCodec {
    /** Volcado NTAG215 completo: 135 páginas × 4 B. */
    const val DUMP_BYTES: Int = 540

    /** Longitud en hex del UID NTAG215 de 7 bytes. */
    const val UID_HEX_CHARS: Int = 14

    /** Línea canónica enviada cuando se retira el Amiibo. */
    const val REMOVED_LINE: String = "{\"type\":\"amiibo_tag\",\"present\":false}"

    /** Bytes ya codificados de una lectura de [dump] completo. */
    fun present(
        uid: String,
        dump: ByteArray,
    ): String {
        require(uid.length == UID_HEX_CHARS && uid.all { it.isDigit() || it in 'a'..'f' }) {
            "uid fuera del contrato: $uid"
        }
        require(dump.size == DUMP_BYTES) { "volcado fuera del contrato: ${dump.size} B" }
        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(dump)
        return "{\"type\":\"amiibo_tag\",\"present\":true,\"uid\":\"$uid\",\"data\":\"$data\"}"
    }

    /** Recorta un volcado mayor a las 135 páginas reales del contrato. */
    fun truncateDump(raw: ByteArray): ByteArray {
        require(raw.size >= DUMP_BYTES) { "volcado incompleto: ${raw.size} B" }
        return raw.copyOf(DUMP_BYTES)
    }
}
