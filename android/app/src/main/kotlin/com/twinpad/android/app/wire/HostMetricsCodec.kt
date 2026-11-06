package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Mensaje `host_metrics` del server (CR-076 v2): consumo del HOST (la PC
 * que corre el twinpad-server) para los gauges de la ventana de espera del
 * teléfono. `gpuPercent` queda null cuando el host no expone el busy del
 * driver (el gauge muestra «—») y `gameFps` es la tasa de publicación del
 * juego seleccionado (Δgfn/Δs del Plugin vivo; null sin juego/frames).
 * CR-085: `cpuTempC`/`gpuTempC` (°C del hwmon, null sin sensor) y
 * CR-086: VRAM usada/total (MiB, ambas null sin par DRM válido) conservan
 * el mismo patrón de «sin dato». */
data class HostMetrics(
    val cpuPercent: Int,
    val gpuPercent: Int?,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val gameFps: Float?,
    val cpuTempC: Int? = null,
    val gpuTempC: Int? = null,
    val vramUsedMb: Long? = null,
    val vramTotalMb: Long? = null,
)

/**
 * Decodificador de `host_metrics` (CR-076 v2). Estricto como el catálogo:
 * campo faltante, porcentaje fuera de 0..100, RAM incoherente o fps
 * negativo → null (la línea no mata el lector de sesión).
 */
object HostMetricsCodec {
    /** Techo de porcentaje de CPU/GPU (paridad con `HOST_METRICS_PERCENT_MAX`). */
    private const val PERCENT_MAX = 100

    /** Techo de °C del u8 del wire (CR-085: temps del hwmon). */
    private const val TEMP_MAX_C = 255

    /** RAM total en MB mínima del contrato (≥1; `0` = línea inválida). */
    private const val RAM_TOTAL_MIN_MB = 1L

    /** Campos crudos ya convertidos (null = ausente/inválido). */
    private data class Raw(
        val cpu: Int?,
        val gpu: Int?,
        val used: Long?,
        val total: Long?,
        val fps: Double?,
        val cpuTemp: Int?,
        val gpuTemp: Int?,
        val vramUsed: Long?,
        val vramTotal: Long?,
    )

    /** Decodifica la línea; null si no es un `host_metrics` válido. */
    fun decode(line: String): HostMetrics? = messageOf(rawOf(line))

    /** Guarda del sobre para campos omitibles (gpu, temps y VRAM): presentes
     * deben ser numéricos y estar en rango; la ausencia es «sin dato». */
    private fun optionalsPresentAndValid(obj: JsonObject): Boolean =
        (!obj.containsKey("gpu_percent") || intValue(obj, "gpu_percent") != null) &&
            (!obj.containsKey("cpu_temp_c") || u8Value(obj, "cpu_temp_c") != null) &&
            (!obj.containsKey("gpu_temp_c") || u8Value(obj, "gpu_temp_c") != null) &&
            (!obj.containsKey("vram_used_mb") || longValue(obj, "vram_used_mb") != null) &&
            (!obj.containsKey("vram_total_mb") || longValue(obj, "vram_total_mb") != null)

    /** Parse crudo con las guardas del sobre: `type` válido y los campos
     * omitibles presentes en rango (la ausencia es «sin dato»). */
    private fun rawOf(line: String): Raw? =
        runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?.takeIf { obj ->
                obj["type"]?.jsonPrimitive?.content == "host_metrics" &&
                    optionalsPresentAndValid(obj)
            }?.let { obj ->
                Raw(
                    cpu = intValue(obj, "cpu_percent"),
                    gpu = obj["gpu_percent"]?.let { _ -> intValue(obj, "gpu_percent") },
                    used = longValue(obj, "ram_used_mb"),
                    total = longValue(obj, "ram_total_mb"),
                    fps = obj["game_fps"]?.let { _ -> doubleValue(obj, "game_fps") },
                    cpuTemp = obj["cpu_temp_c"]?.let { _ -> u8Value(obj, "cpu_temp_c") },
                    gpuTemp = obj["gpu_temp_c"]?.let { _ -> u8Value(obj, "gpu_temp_c") },
                    vramUsed = obj["vram_used_mb"]?.let { _ -> longValue(obj, "vram_used_mb") },
                    vramTotal = obj["vram_total_mb"]?.let { _ -> longValue(obj, "vram_total_mb") },
                )
            }

    /** Contrato del `host_metrics` v1: CPU requerida (0..100), GPU solo
     * 0..100 si viene, RAM coherente (0 ≤ usada ≤ total ≥ 1) y fps ≥ 0. */
    private fun messageOf(raw: Raw?): HostMetrics? =
        raw
            ?.takeIf { parts -> parts.cpu != null && parts.cpu >= 0 && parts.cpu <= PERCENT_MAX }
            ?.takeIf { parts -> parts.gpu == null || (parts.gpu >= 0 && parts.gpu <= PERCENT_MAX) }
            ?.takeIf { parts ->
                parts.used != null && parts.used >= 0 &&
                    parts.total != null && parts.total >= RAM_TOTAL_MIN_MB &&
                    parts.used <= parts.total
            }
            ?.takeIf { parts -> parts.fps == null || parts.fps >= 0.0 }
            ?.takeIf { parts -> vramPairIsValid(parts) }
            ?.let { parts ->
                val cpu = parts.cpu
                val used = parts.used
                val total = parts.total
                if (cpu == null || used == null || total == null) {
                    null
                } else {
                    HostMetrics(
                        cpu,
                        parts.gpu,
                        used,
                        total,
                        parts.fps?.toFloat(),
                        parts.cpuTemp,
                        parts.gpuTemp,
                        parts.vramUsed,
                        parts.vramTotal,
                    )
                }
            }

    /** Par VRAM (CR-086): ausente en ambos = «sin dato»; presente solo junto
     * y con 0 ≤ usada ≤ total ≥ 1. Par parcial o incoherente invalida la línea. */
    private fun vramPairIsValid(parts: Raw): Boolean {
        val used = parts.vramUsed
        val total = parts.vramTotal
        if (used == null && total == null) {
            return true
        }
        if (used == null || total == null) {
            return false
        }
        return used >= 0L && total > 0L && used <= total
    }

    private fun intValue(
        obj: JsonObject,
        key: String,
    ): Int? =
        obj[key]?.let { element -> runCatching { element.jsonPrimitive.int }.getOrNull() }
            ?.takeIf { percent -> percent in 0..PERCENT_MAX }

    private fun longValue(
        obj: JsonObject,
        key: String,
    ): Long? =
        obj[key]?.let { element -> runCatching { element.jsonPrimitive.long }.getOrNull() }
            ?.takeIf { value -> value >= 0L }

    private fun doubleValue(
        obj: JsonObject,
        key: String,
    ): Double? = obj[key]?.let { element -> runCatching { element.jsonPrimitive.double }.getOrNull() }

    /** u8 del wire (CR-085: °C del hwmon 0..255; presencia promovida por
     * el gate del sobre). */
    private fun u8Value(
        obj: JsonObject,
        key: String,
    ): Int? =
        obj[key]?.let { element -> runCatching { element.jsonPrimitive.int }.getOrNull() }
            ?.takeIf { temp -> temp in 0..TEMP_MAX_C }
}
