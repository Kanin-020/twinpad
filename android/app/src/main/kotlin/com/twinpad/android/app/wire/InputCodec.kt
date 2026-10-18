package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instantánea completa del estado del gamepad — SPEC-004 §2/§4 (Signature
 * HID, CTX-C5). La app la compone con cada cambio y la envía con `seq`
 * creciente (R2).
 */
data class InputReport(
    val seq: Int,
    val stickLx: Int,
    val stickLy: Int,
    val stickRx: Int,
    val stickRy: Int,
    val dpad: Int,
    val buttons: Int,
    val triggerL2: Int,
    val triggerR2: Int,
    /** Orientación absoluta yaw/pitch/roll en vueltas int16; null si no está disponible. */
    val gyro: List<Int>? = null,
    /** Posición sobre la imagen DRC normalizada a 0…65535. */
    val touchX: Int = 0,
    val touchY: Int = 0,
    /** Estado del contacto en la imagen DRC. */
    val touchPressed: Boolean = false,
)

/**
 * Codificador de `input_report` — paridad con `core::input` (SPEC-004 §4):
 * NDJSON con `type` como discriminante y `gyro` omitido cuando es `null`.
 */
object InputCodec {
    private val json = Json

    /** Serializa el reporte a su línea NDJSON canónica (sin `\n` final). */
    fun encode(report: InputReport): String {
        val obj =
            buildJsonObject {
                put("type", "input_report")
                put("seq", report.seq)
                put("stick_lx", report.stickLx)
                put("stick_ly", report.stickLy)
                put("stick_rx", report.stickRx)
                put("stick_ry", report.stickRy)
                put("dpad", report.dpad)
                put("buttons", report.buttons)
                put("trigger_l2", report.triggerL2)
                put("trigger_r2", report.triggerR2)
                report.gyro?.let { axes ->
                    put("gyro", kotlinx.serialization.json.JsonArray(axes.map { JsonPrimitive(it) }))
                }
                put("touch_x", report.touchX.coerceIn(0, ProtocolConstants.TOUCH_MAX))
                put("touch_y", report.touchY.coerceIn(0, ProtocolConstants.TOUCH_MAX))
                put("touch_pressed", report.touchPressed)
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
