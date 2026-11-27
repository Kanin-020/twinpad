package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Intensidades del rumble del DualSense virtual (CR-131). */
data class RumbleMotorsMessage(val strong: Int, val weak: Int)

/**
 * Decodificador de `rumble_motors` (SPEC-006 §4, CR-131). Estricto:
 * `type` distinto, campos extra, `strong`/`weak` faltantes, no enteros o
 * fuera de 0…255 → null.
 */
object RumbleMotorsCodec {
    private val expectedKeys = setOf("type", "strong", "weak")
    private const val MAX_MOTOR = 255

    fun decode(line: String): RumbleMotorsMessage? =
        runCatching {
            val obj = Json.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
            if (obj.keys != expectedKeys) return@runCatching null
            val type = obj["type"]?.jsonPrimitive ?: return@runCatching null
            if (!type.isString || type.content != "rumble_motors") return@runCatching null
            val strong = obj["strong"]?.jsonPrimitive ?: return@runCatching null
            val weak = obj["weak"]?.jsonPrimitive ?: return@runCatching null
            if (strong.isString || weak.isString) return@runCatching null
            val strongValue = strong.intOrNull ?: return@runCatching null
            val weakValue = weak.intOrNull ?: return@runCatching null
            if (strongValue !in 0..MAX_MOTOR || weakValue !in 0..MAX_MOTOR) return@runCatching null
            RumbleMotorsMessage(strongValue, weakValue)
        }.getOrNull()
}
