package com.twinpad.android.app.ui

import android.app.ActivityManager
import android.content.Context
import android.view.Choreographer
import java.io.File

/**
 * CR-076: medición de consumo del DISPOSITIVO para los gauges de la ventana
 * de espera (CPU/GPU/RAM/FPS del teléfono). El cómputo vive en tipos puros
 * (testeables en JVM); los adaptadores Android solo leen `/proc`, los sysfs
 * del driver de GPU y `ActivityManager.MemoryInfo`. La GPU no tiene API
 * estándar: si ningún sysfs expone el busy del driver, el gauge queda sin
 * dato («—») y no se inventan números.
 */
object DeviceMetricsSampler {
    /** Snapshot de /proc/stat para el cálculo delta de CPU. */
    data class CpuSnapshot(
        val busyJiffies: Long,
        val totalJiffies: Long,
    )

    /** Lectura de RAM en bytes (usado vs total). */
    data class RamReading(
        val usedBytes: Long,
        val totalBytes: Long,
    ) {
        /** % de uso 0..100 (0 si total no es positivo: sin total no hay %). */
        val usedPercent: Int
            get() =
                if (totalBytes > 0) {
                    (((usedBytes * 100) / totalBytes).toInt()).coerceIn(0, 100)
                } else {
                    0
                }

        /** GB usados con un decimal (texto del gauge). */
        val usedGbText: String get() = "%.1f GB".format(usedBytes.toDouble() / GIB)
    }

    /** Ventana deslizante de ticks de frame para estimar FPS. */
    class FpsWindow(
        private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    ) {
        private val ticks = ArrayDeque<Long>()

        /** Registra un frame del Choreographer (timestamp ns monotónico). */
        fun addFrameTick(timestampNanos: Long) {
            ticks.addLast(timestampNanos)
            while (ticks.size > 1 && timestampNanos - ticks.first() > windowNanos) {
                ticks.removeFirst()
            }
        }

        /** FPS estimado: frames observados en el lapso de la ventana. */
        fun fps(): Double {
            if (ticks.size < 2) return 0.0
            val elapsedNanos = ticks.last() - ticks.first()
            if (elapsedNanos <= 0) return 0.0
            return (ticks.size - 1) * NANOS_PER_SECOND / elapsedNanos.toDouble()
        }

        private companion object {
            const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
        }
    }

    /** Parseo de la primera línea de /proc/stat («cpu u n i iow irq …»). */
    fun parseCpuStat(line: String): CpuSnapshot? {
        val fields = line.trim().split(WHITESPACE)
        if (fields.size < MIN_CPU_FIELDS || fields.first() != CPU_TOKEN) return null
        val counts = fields.drop(1).mapNotNull(String::toLongOrNull)
        if (counts.size != fields.size - 1) return null
        val idle = counts[IDLE_INDEX] + counts[IOWAIT_INDEX]
        val total = counts.sum()
        return if (total <= 0) null else CpuSnapshot(busyJiffies = total - idle, totalJiffies = total)
    }

    /** % de uso CPU 0..100 entre dos snapshots; null si no hay delta válido. */
    fun cpuUsagePercent(
        prev: CpuSnapshot,
        now: CpuSnapshot,
    ): Int? {
        val dTotal = now.totalJiffies - prev.totalJiffies
        val dBusy = now.busyJiffies - prev.busyJiffies
        if (dTotal <= 0 || dBusy < 0) return null
        return (((dBusy * PERCENT_SCALE) / dTotal).toInt()).coerceIn(0, PERCENT_SCALE)
    }

    /** Parseo del busy del driver GPU; acepta «37 %» y «37%»; null si la
     * línea no es un porcentaje puro al inicio (detrás un «-» o fuera de
     * 0..100 no es un busy válido). */
    fun parseGpuBusy(line: String): Int? =
        GPU_BUSY_LINE
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..PERCENT_SCALE }

    /** Lee un snapshot CPU de /proc/stat (null si la línea no parsea). */
    fun readCpuSnapshot(): CpuSnapshot? =
        runCatching {
            File(CPU_STAT_PATH).useLines { lines -> lines.firstOrNull() }
        }.getOrNull()?.let(::parseCpuStat)

    /** Busy del driver GPU: primer sysfs conocido que exponga el porcentaje. */
    fun readGpuBusyPercent(): Int? =
        GPU_BUSY_PATHS.firstNotNullOfOrNull { path ->
            runCatching { parseGpuBusy(File(path).readText()) }.getOrNull()
        }

    /** RAM del dispositivo: uso/total del ActivityManager. */
    fun readRamReading(context: Context): RamReading {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val total = memoryInfo.totalMem
        val used = (total - memoryInfo.availMem).coerceAtLeast(0)
        return RamReading(usedBytes = used, totalBytes = total)
    }

    /** FPS con ticks del Choreographer mientras el callback esté activo. */
    class FpsMonitor(
        private val window: FpsWindow = FpsWindow(),
    ) {
        private var running = false

        private val callback: Choreographer.FrameCallback =
            Choreographer.FrameCallback { frameTimeNanos ->
                if (!running) return@FrameCallback
                window.addFrameTick(frameTimeNanos)
                Choreographer.getInstance().postFrameCallback(callback)
            }

        /** Empieza a medir (idempotente). */
        fun start() {
            if (running) return
            running = true
            Choreographer.getInstance().postFrameCallback(callback)
        }

        /** Deja de medir (idempotente). */
        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(callback)
        }

        /** FPS actual de la ventana (los ticks siguen entrando mientras corra). */
        fun fps(): Double = window.fps()
    }

    private val WHITESPACE = Regex("""\s+""")
    private const val CPU_STAT_PATH = "/proc/stat"
    private const val CPU_TOKEN = "cpu"
    private const val IDLE_INDEX = 3
    private const val IOWAIT_INDEX = 4
    private const val MIN_CPU_FIELDS = 5
    private const val PERCENT_SCALE = 100
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private const val NANOS_PER_SECOND = 1.0e9

    /** Línea «NN %» al inicio (kgsl y variantes comunes). */
    private val GPU_BUSY_LINE = Regex("""^\s*(\d{1,3})\s*%""")

    // sysfs que exponen el busy del driver GPU (en orden de preferencia:
    // Adreno kgsl, hubs tipo Tegra/Pulse, Mali; varía por SoC).
    private val GPU_BUSY_PATHS =
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilisation",
        )
}
