package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Estado del mando virtual del Servidor (CR-130). */
data class VirtualPadStateMessage(val on: Boolean)

/**
 * Decodificador de `virtual_pad_state` (SPEC-006 §4, CR-130). Estricto:
 * `type` distinto, campos extra, `on` faltante, no booleano o JSON inválido
 * → null.
 */
object VirtualPadStateCodec {
    private val expectedKeys = setOf("type", "on")

    fun decode(line: String): VirtualPadStateMessage? =
        runCatching {
            val obj = Json.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
            if (obj.keys != expectedKeys) return@runCatching null
            val type = obj["type"]?.jsonPrimitive ?: return@runCatching null
            if (!type.isString || type.content != "virtual_pad_state") return@runCatching null
            val on = obj["on"]?.jsonPrimitive ?: return@runCatching null
            if (on.isString) return@runCatching null
            VirtualPadStateMessage(on.booleanOrNull ?: return@runCatching null)
        }.getOrNull()
}
