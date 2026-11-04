package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
    /** CR-075: backend del encoder activo en el Plugin (opcional; null sin clave). */
    val hardwareEncoder: Boolean? = null,
    /** CR-075: resolución de entrada (fuente del juego, pre-escala) o null. */
    val inputWidth: Int? = null,
    val inputHeight: Int? = null,
    /** CR-075: resolución de salida (post-escala, lo que viaja al stream). */
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
)

/** Ventana declarada del mensaje, entre 10 y 10000 ms (CR-053). */
private const val VIDEO_WINDOW_MIN_MS = 10
private const val VIDEO_WINDOW_MAX_MS = 10_000

/** Dimensión de video válida del mensaje CR-075: 1…8192 px. */
private const val VIDEO_DIM_MAX = 8192

/** `count` válido de etapa o evento: entero en 1…100000 (CR-053). */
private const val VIDEO_COUNT_MAX = 100_000

/** División µs → ms del rótulo compacto del HUD. */
private const val US_PER_MS = 1_000

/**
 * Decodificador de `video_stage_stats` (CR-053). Estricto, paridad con
 * `core::game`: `type` distinto, ventana fuera de 10–10000 ms, etapa/evento
 * fuera del catálogo, campo faltante, `count` < 1 o > 100000, `avg_us` >
 * `max_us`, arrays más largos que el catálogo o etapa/evento repetido → null.
 */
object VideoStageStatsCodec {
    fun decode(line: String): VideoStageStatsMessage? =
        runCatching { Json.parseToJsonElement(line) as? JsonObject }
            .getOrNull()
            ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "video_stage_stats" }
            ?.let(::decodeBody)

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

    private fun usText(us: Int): String {
        return if (us >= US_PER_MS) "%.1f ms".format(us / US_PER_MS.toDouble()) else "$us µs"
    }

    /** CR-075: línea de detalles del HUD con solo lo conocido: `Acel. HW: sí ·
     * Trans: UDP · E: 854×480 · S: 960×540`; null sin ningún dato. `transport`
     * ya llega rotulada por el llamante (UDP/TCP o null sin decisión). */
    fun formatDetails(
        metrics: VideoStageStatsMessage,
        transport: String? = null,
    ): String? {
        val parts =
            buildList {
                metrics.hardwareEncoder?.let { add("HW: ${if (it) "sí" else "no"}") }
                transport?.let { add("Trans: $it") }
                dimsText(metrics.inputWidth, metrics.inputHeight)?.let { add("E: $it") }
                dimsText(metrics.outputWidth, metrics.outputHeight)?.let { add("S: $it") }
            }
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    private fun dimsText(
        width: Int?,
        height: Int?,
    ): String? {
        return if (width != null && height != null) "$width×$height" else null
    }
}

/** Cuerpo del mensaje: ventana, etapas y eventos; null si algo falla (CR-053). */
private fun decodeBody(obj: JsonObject): VideoStageStatsMessage? =
    obj["window_ms"]?.jsonPrimitive?.intOrNull
        ?.takeIf { it in VIDEO_WINDOW_MIN_MS..VIDEO_WINDOW_MAX_MS }
        ?.let { windowMs ->
            obj["stages"]?.jsonArray?.mapAll(::decodeStage)?.let { stages ->
                obj["events"]?.jsonArray?.mapAll(::decodeEvent)?.let { events ->
                    decodeDetails(obj, windowMs, stages, events)
                }
            }
        }

/** Une catálogo (CR-053) y detalles opcionales CR-075 sin repetidos. */
private fun assembledStats(
    windowMs: Int,
    stages: List<VideoStageStatMessage>,
    events: List<VideoStageEventMessage>,
    details: StatsDetails,
): VideoStageStatsMessage? {
    val inCatalog =
        stages.size <= VIDEO_STAGE_WIRE_NAMES.size && events.size <= VIDEO_EVENT_WIRE_NAMES.size
    val unique =
        stages.distinctBy { it.stage }.size == stages.size &&
            events.distinctBy { it.event }.size == events.size
    return if (inCatalog && unique) {
        VideoStageStatsMessage(
            windowMs = windowMs,
            stages = stages,
            events = events,
            hardwareEncoder = details.hardware,
            inputWidth = details.input?.first,
            inputHeight = details.input?.second,
            outputWidth = details.output?.first,
            outputHeight = details.output?.second,
        )
    } else {
        null
    }
}

/** Detalles opcionales CR-075 del mensaje. */
private data class StatsDetails(
    val hardware: Boolean?,
    val input: Pair<Int, Int>?,
    val output: Pair<Int, Int>?,
)

/** Lee hw/in/out del objeto; valor inválido o parcial → null (no invalida). */
private fun decodeDetails(
    obj: JsonObject,
    windowMs: Int,
    stages: List<VideoStageStatMessage>,
    events: List<VideoStageEventMessage>,
): VideoStageStatsMessage? {
    val details =
        StatsDetails(
            hardware = obj["hw"]?.jsonPrimitive?.booleanOrNull,
            input = readDims(obj, "in_w", "in_h"),
            output = readDims(obj, "out_w", "out_h"),
        )
    return assembledStats(windowMs, stages, events, details)
}

/** Par (w,h) del mensaje si ambas claves son válidas; si no, null. */
private fun readDims(
    obj: JsonObject,
    widthKey: String,
    heightKey: String,
): Pair<Int, Int>? {
    val width = obj[widthKey]?.jsonPrimitive?.intOrNull
    val height = obj[heightKey]?.jsonPrimitive?.intOrNull
    if (width == null || height == null) return null
    val dimsValid =
        width >= 1 && width <= VIDEO_DIM_MAX && height >= 1 && height <= VIDEO_DIM_MAX
    return if (dimsValid) width to height else null
}

/** Decodifica el array entero con [decode] o falla (un elemento malo → null). */
private inline fun <T> JsonArray.mapAll(decode: (JsonElement) -> T?): List<T>? {
    val out = ArrayList<T>(size)
    for (element in this) {
        out.add(decode(element) ?: return null)
    }
    return out
}

/** Etapa del catálogo con `count` en 1…100000 y `avg_us` ≤ `max_us`, o null. */
private fun decodeStage(element: JsonElement): VideoStageStatMessage? =
    (element as? JsonObject)?.let { item ->
        item["stage"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in VIDEO_STAGE_WIRE_NAMES }
            ?.let { stage ->
                stageCounts(item)?.let { (count, avgMax) ->
                    VideoStageStatMessage(stage, count, avgMax.first, avgMax.second)
                }
            }
    }

/** `(count, (avgUs, maxUs))` válidos de una etapa; null si falta algo o excede. */
private fun stageCounts(item: JsonObject): Pair<Int, Pair<Int, Int>>? =
    item["count"]?.jsonPrimitive?.intOrNull
        ?.takeIf { it in 1..VIDEO_COUNT_MAX }
        ?.let { count -> avgMaxOf(item)?.let { count to it } }

/** `avg_us` ≥ 0 y `avg_us` ≤ `max_us`; ambos presentes (CR-053). */
private fun avgMaxOf(item: JsonObject): Pair<Int, Int>? {
    val avgUs = item["avg_us"]?.jsonPrimitive?.intOrNull ?: return null
    val maxUs = item["max_us"]?.jsonPrimitive?.intOrNull ?: return null
    return if (avgUs < 0 || avgUs > maxUs) null else avgUs to maxUs
}

/** Evento del catálogo con `count` en 1…100000, o null (CR-053). */
private fun decodeEvent(element: JsonElement): VideoStageEventMessage? =
    (element as? JsonObject)?.let { item ->
        item["event"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in VIDEO_EVENT_WIRE_NAMES }
            ?.let { event ->
                item["count"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..VIDEO_COUNT_MAX }
                    ?.let { VideoStageEventMessage(event, it) }
            }
    }
