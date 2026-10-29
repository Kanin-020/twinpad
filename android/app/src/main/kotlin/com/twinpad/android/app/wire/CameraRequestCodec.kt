package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Demanda vigente del video de la cámara del teléfono (CR-038). */
data class CameraRequestMessage(val on: Boolean)

/**
 * Decodificador de `camera_request` (SPEC-006 §4, CR-038). Estricto: `type`
 * distinto, flag `on` faltante o no booleano → null.
 */
object CameraRequestCodec {
    fun decode(line: String): CameraRequestMessage? {
        val obj =
            runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (obj?.get("type")?.jsonPrimitive?.content != "camera_request") return null
        val on = obj["on"]?.jsonPrimitive?.booleanOrNull ?: return null
        return CameraRequestMessage(on)
    }
}
