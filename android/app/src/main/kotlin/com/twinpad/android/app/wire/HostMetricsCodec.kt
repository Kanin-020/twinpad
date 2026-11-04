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
 * juego seleccionado (Δgfn/Δs del Plugin vivo; null sin juego/frames). */
data class HostMetrics(
    val cpuPercent: Int,
    val gpuPercent: Int?,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val gameFps: Float?,
)

/**
 * Decodificador de `host_metrics` (CR-076 v2). Estricto como el catálogo:
 * campo faltante, porcentaje fuera de 0..100, RAM incoherente o fps
 * negativo → null (la línea no mata el lector de sesión).
 */
object HostMetricsCodec {
    /** Techo de porcentaje de CPU/GPU (paridad con `HOST_METRICS_PERCENT_MAX`). */
    private const val PERCENT_MAX = 100

    /** RAM total en MB mínima del contrato (≥1; `0` = línea inválida). */
    private const val RAM_TOTAL_MIN_MB = 1L

    /** Campos crudos ya convertidos (null = ausente/inválido). */
    private data class Raw(
        val cpu: Int?,
        val gpu: Int?,
        val used: Long?,
        val total: Long?,
        val fps: Double?,
    )

    /** Decodifica la línea; null si no es un `host_metrics` válido. */
    fun decode(line: String): HostMetrics? = messageOf(rawOf(line))

    /** Parse crudo con las guardas del sobre: `type` válido y un
     * `gpu_percent` PRESENTE debe ser numérico 0..100 (la ausencia NO
     * rechaza: es «sin dato» del conductor). */
    private fun rawOf(line: String): Raw? =
        runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?.takeIf { obj ->
                obj["type"]?.jsonPrimitive?.content == "host_metrics" &&
                    (
                        !obj.containsKey("gpu_percent") ||
                            intValue(obj, "gpu_percent") != null
                    )
            }?.let { obj ->
                Raw(
                    cpu = intValue(obj, "cpu_percent"),
                    gpu = obj["gpu_percent"]?.let { _ -> intValue(obj, "gpu_percent") },
                    used = longValue(obj, "ram_used_mb"),
                    total = longValue(obj, "ram_total_mb"),
                    fps = obj["game_fps"]?.let { _ -> doubleValue(obj, "game_fps") },
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
            }?.takeIf { parts -> parts.fps == null || parts.fps >= 0.0 }
            ?.let { parts ->
                val cpu = parts.cpu
                val used = parts.used
                val total = parts.total
                if (cpu == null || used == null || total == null) {
                    null
                } else {
                    HostMetrics(cpu, parts.gpu, used, total, parts.fps?.toFloat())
                }
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
    ): Long? = obj[key]?.let { element -> runCatching { element.jsonPrimitive.long }.getOrNull() }

    private fun doubleValue(
        obj: JsonObject,
        key: String,
    ): Double? = obj[key]?.let { element -> runCatching { element.jsonPrimitive.double }.getOrNull() }
}
