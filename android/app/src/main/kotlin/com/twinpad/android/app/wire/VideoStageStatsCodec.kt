package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Etapas canónicas de `video_stage_stats` (CR-053; paridad con `core::game`). */
val VIDEO_STAGE_WIRE_NAMES: List<String> =
    listOf(
        "readback_copy",
        "readback_ready",
        "pixel_convert",
        "scale",
        "i420_convert",
        "hardware_encoder",
        "openh264",
        "encode_pipeline",
        "base64_json",
        "ipc_send",
    )

/** Rótulo compacto por etapa para el HUD (vínculo con el log `[TwinpadPerf]`). */
private val STAGE_SHORT_LABELS: Map<String, String> =
    mapOf(
        "readback_copy" to "rbCopy",
        "readback_ready" to "rbReady",
        "pixel_convert" to "pix",
        "scale" to "scale",
        "i420_convert" to "i420",
        "hardware_encoder" to "hwEnc",
        "openh264" to "swEnc",
        "encode_pipeline" to "encode",
        "base64_json" to "b64",
        "ipc_send" to "ipc",
    )

/** Eventos canónicos de flujo (CR-053; paridad con `core::game`). */
val VIDEO_EVENT_WIRE_NAMES: List<String> =
    listOf(
        "drc_callback",
        "capture_accepted",
        "readback_queued",
        "readback_completed",
        "readback_slot_busy",
        "frame_queued",
        "frame_encoded",
        "frame_sent",
    )

/** Métrica agregada de una etapa en la ventana (CR-053). */
data class VideoStageStatMessage(val stage: String, val count: Int, val avgUs: Int, val maxUs: Int)

/** Contador agregado de un evento de flujo en la ventana (CR-053). */
data class VideoStageEventMessage(val event: String, val count: Int)

/** Mensaje `video_stage_stats` (CR-053): etapas+eventos del Plugin en la ventana. */
data class VideoStageStatsMessage(
    val windowMs: Int,
    val stages: List<VideoStageStatMessage>,
    val events: List<VideoStageEventMessage>,
)

/**
 * Decodificador de `video_stage_stats` (CR-053). Estricto, paridad con
 * `core::game`: `type` distinto, ventana fuera de 10–10000 ms, etapa/evento
 * fuera del catálogo, campo faltante, `count` < 1 o > 100000, `avg_us` >
 * `max_us`, arrays más largos que el catálogo o etapa/evento repetido → null.
 */
object VideoStageStatsCodec {
    private const val WINDOW_MIN_MS = 10
    private const val WINDOW_MAX_MS = 10_000
    private const val COUNT_MAX = 100_000

    fun decode(line: String): VideoStageStatsMessage? {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (obj?.get("type")?.jsonPrimitive?.contentOrNull != "video_stage_stats") return null
        val windowMs = obj["window_ms"]?.jsonPrimitive?.intOrNull ?: return null
        if (windowMs < WINDOW_MIN_MS || windowMs > WINDOW_MAX_MS) return null
        val stages =
            obj["stages"]?.jsonArray?.let(::decodeStages) ?: return null
        val events =
            obj["events"]?.jsonArray?.let(::decodeEvents) ?: return null
        if (stages.size > VIDEO_STAGE_WIRE_NAMES.size || events.size > VIDEO_EVENT_WIRE_NAMES.size) {
            return null
        }
        if (stages.distinctBy { it.stage }.size != stages.size ||
            events.distinctBy { it.event }.size != events.size
        ) {
            return null
        }
        return VideoStageStatsMessage(windowMs, stages, events)
    }

    /** Decodifica el array completo o falla (uno malo anula el mensaje). */
    private fun decodeStages(array: JsonArray): List<VideoStageStatMessage>? {
        val out = ArrayList<VideoStageStatMessage>(array.size)
        for (element in array) {
            out.add(decodeStage(element) ?: return null)
        }
        return out
    }

    /** Decodifica el array de eventos completo o falla. */
    private fun decodeEvents(array: JsonArray): List<VideoStageEventMessage>? {
        val out = ArrayList<VideoStageEventMessage>(array.size)
        for (element in array) {
            out.add(decodeEvent(element) ?: return null)
        }
        return out
    }

    private fun decodeStage(element: JsonElement): VideoStageStatMessage? {
        val item = element as? JsonObject ?: return null
        val stage = item["stage"]?.jsonPrimitive?.contentOrNull ?: return null
        if (stage !in VIDEO_STAGE_WIRE_NAMES) return null
        val count = item["count"]?.jsonPrimitive?.intOrNull ?: return null
        val avgUs = item["avg_us"]?.jsonPrimitive?.intOrNull ?: return null
        val maxUs = item["max_us"]?.jsonPrimitive?.intOrNull ?: return null
        if (count < 1 || count > COUNT_MAX || avgUs < 0 || avgUs > maxUs) return null
        return VideoStageStatMessage(stage, count, avgUs, maxUs)
    }

    private fun decodeEvent(element: JsonElement): VideoStageEventMessage? {
        val item = element as? JsonObject ?: return null
        val event = item["event"]?.jsonPrimitive?.contentOrNull ?: return null
        if (event !in VIDEO_EVENT_WIRE_NAMES) return null
        val count = item["count"]?.jsonPrimitive?.intOrNull ?: return null
        if (count < 1 || count > COUNT_MAX) return null
        return VideoStageEventMessage(event, count)
    }

    /** Rótulo compacto de una etapa para el HUD. */
    fun shortLabel(stage: String): String = STAGE_SHORT_LABELS[stage] ?: stage

    /**
     * Texto de la línea HUD: `pix 0.21 ms · b64 0.06 ms …`. `null` sin
     * etapas ni eventos (silencio en el chip en vez de una línea vacía).
     */
    fun format(metrics: VideoStageStatsMessage): String? {
        if (metrics.stages.isEmpty() && metrics.events.isEmpty()) return null
        val stageParts =
            metrics.stages.map { stat ->
                "${shortLabel(stat.stage)} ${usText(stat.avgUs)} (${usText(stat.maxUs)})"
            }
        val eventText =
            if (metrics.events.isEmpty()) null else "${metrics.events.sumOf { it.count }} ev"
        val parts = stageParts + listOfNotNull(eventText)
        return parts.joinToString(" · ")
    }

    private fun usText(us: Int): String =
        if (us >= 1000) "%.1f ms".format(us / 1000.0) else "$us µs"
}
