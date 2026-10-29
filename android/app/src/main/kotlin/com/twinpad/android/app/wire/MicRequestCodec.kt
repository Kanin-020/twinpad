package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Demanda vigente del micrófono del teléfono (CR-035). */
data class MicRequestMessage(val on: Boolean)

/**
 * Decodificador de `mic_request` (SPEC-006 §4, CR-035). Estricto: `type`
 * distinto, flag `on` faltante o no booleano → null.
 */
object MicRequestCodec {
    fun decode(line: String): MicRequestMessage? {
        val obj =
            runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (obj?.get("type")?.jsonPrimitive?.content != "mic_request") return null
        val on = obj["on"]?.jsonPrimitive?.booleanOrNull ?: return null
        return MicRequestMessage(on)
    }
}
