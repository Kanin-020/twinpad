package com.twinpad.android.app.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.R
import com.twinpad.android.app.wire.HostMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Techo del arco del gauge FPS de la ventana de espera (CR-076 v2: 60). */
private const val GAUGE_MAX_FPS = 60f

/** Total del arco de porcentajes (CR-076: todos los gauges usan 0..100). */
private const val GAUGE_MAX_PERCENT = 100f

/** Decimal único del GB de RAM (formato del gauge, PC y Gamepad). */
private const val GB_TEXT_FORMAT = "%.1f GB"

/** FPS con una forma entera para el texto del gauge. */
private const val FPS_TEXT_FORMAT = "%.0f"

/** Sin dato: el gauge muestra «—» con el anillo atenuado (nunca inventos). */
private const val NO_DATA_TEXT = "—"

/**
 * CR-077: panel de la retícula 2×2 de gauges de la ventana de espera
 * (CR-076 v3). Dueño único del pintado por origen elegido en el drawer:
 * PC (`host_metrics` del server, CR-076) o el propio teléfono Gamepad
 * ([DeviceMetricsSampler]). Consolidar aquí saca de [PairingActivity] el
 * bloque de gauges (detekt LargeClass/TooManyFunctions) y concentra la
 * I/O de `/proc`/sysfs/ActivityManager fuera del hilo de UI (ENG §9).
 */
internal class WaitingGaugePanel(
    private val activity: AppCompatActivity,
) {
    /** Etiquetas escritas una sola vez (CR-076 v2); el valor se re-escribe. */
    private var labelsInitialized = false

    /** CR-077: estado del muestreo del Gamepad: CPU previa (delta) y FPS
     * del render por el Choreographer del dispositivo (idempotente). */
    private var deviceCpuPrev: DeviceMetricsSampler.CpuSnapshot? = null
    private var deviceFpsMonitor: DeviceMetricsSampler.FpsMonitor? = null

    /** Snapshot crudo del dispositivo ya leído fuera del hilo de UI. */
    private data class DeviceSnapshot(
        val cpuPercent: Int?,
        val gpuPercent: Int?,
        val ram: DeviceMetricsSampler.RamReading,
        val fps: Double,
    )

    /** Etiquetas CPU/GPU/RAM/FPS con los recursos canónicos (R4). */
    private val ensureLabels: () -> Unit = {
        if (!labelsInitialized) {
            mapOf(
                R.id.gauge_cpu to R.string.metrics_cpu,
                R.id.gauge_gpu to R.string.metrics_gpu,
                R.id.gauge_ram to R.string.metrics_ram,
                R.id.gauge_fps to R.string.metrics_fps,
            ).forEach { (id, label) ->
                gaugeOf(activity, id).label = activity.getString(label)
            }
            labelsInitialized = true
        }
    }

    /** CR-076 v2: gauges del HOST — CPU/GPU %, RAM GB con arco % y FPS
     * del juego seleccionado; lo desconocido queda «—» (sin inventos). */
    fun renderHost(metrics: HostMetrics?) {
        ensureLabels()
        renderPercent(R.id.gauge_cpu, metrics?.cpuPercent)
        renderPercent(R.id.gauge_gpu, metrics?.gpuPercent)
        val ramGauge = gaugeOf(activity, R.id.gauge_ram)
        if (metrics == null || metrics.ramTotalMb < 1L) {
            ramGauge.hasValue = false
            ramGauge.valueText = NO_DATA_TEXT
        } else {
            ramGauge.hasValue = true
            ramGauge.progress = metrics.ramUsedMb.toFloat() / metrics.ramTotalMb
            ramGauge.valueText = GB_TEXT_FORMAT.format(metrics.ramUsedMb.toDouble() / MB_PER_GB)
        }
        val fpsGauge = gaugeOf(activity, R.id.gauge_fps)
        val fps = metrics?.gameFps
        if (fps == null) {
            fpsGauge.hasValue = false
            fpsGauge.valueText = NO_DATA_TEXT
        } else {
            fpsGauge.hasValue = true
            fpsGauge.progress = (fps / GAUGE_MAX_FPS).coerceAtMost(1f)
            fpsGauge.valueText = FPS_TEXT_FORMAT.format(fps)
        }
    }

    /** CR-077: gauges del teléfono Gamepad — las lecturas pesadas van
     * fuera del hilo de UI; el primer tick de CPU instala el previo (como
     * el sampler del HOST) y el arco de FPS pinta contra el techo 60. */
    fun renderDevice() {
        ensureLabels()
        startDeviceFpsMonitor()
        activity.lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.Default) { sampleDevice() }
            renderPercent(R.id.gauge_cpu, snapshot.cpuPercent)
            renderPercent(R.id.gauge_gpu, snapshot.gpuPercent)
            val ramGauge = gaugeOf(activity, R.id.gauge_ram)
            ramGauge.hasValue = true
            ramGauge.progress = snapshot.ram.usedPercent / GAUGE_MAX_PERCENT
            ramGauge.valueText = snapshot.ram.usedGbText
            val fpsGauge = gaugeOf(activity, R.id.gauge_fps)
            if (snapshot.fps <= 0.0) {
                fpsGauge.hasValue = false
                fpsGauge.valueText = NO_DATA_TEXT
            } else {
                fpsGauge.hasValue = true
                fpsGauge.progress = (snapshot.fps.toFloat() / GAUGE_MAX_FPS).coerceAtMost(1f)
                fpsGauge.valueText = FPS_TEXT_FORMAT.format(snapshot.fps)
            }
        }
    }

    /** CR-077: apagar el monitor de FPS del Gamepad (host otra vez, vista
     * oculta por el stream o Activity destruida). */
    fun stopDeviceMonitor() {
        deviceFpsMonitor?.stop()
        deviceFpsMonitor = null
    }

    /** Porcentajes compartidos de PC/Gamepad; «—» sin dato. */
    private val renderPercent:
        (Int, Int?) -> Unit =
        { id, percent ->
            val gauge = gaugeOf(activity, id)
            if (percent == null) {
                gauge.hasValue = false
                gauge.valueText = NO_DATA_TEXT
            } else {
                gauge.hasValue = true
                gauge.progress = percent / GAUGE_MAX_PERCENT
                gauge.valueText = "$percent%"
            }
        }

    /** Monitor de FPS del render del Gamepad (idempotente). */
    private fun startDeviceFpsMonitor() {
        if (deviceFpsMonitor == null) {
            deviceFpsMonitor = DeviceMetricsSampler.FpsMonitor().also { it.start() }
        }
    }

    /** Sampleo del dispositivo serializado (1 tick/s); el delta de CPU
     * guarda/consume el snapshot previo CR-076. */
    @Synchronized
    private fun sampleDevice(): DeviceSnapshot {
        val now = DeviceMetricsSampler.readCpuSnapshot()
        val prev = deviceCpuPrev
        val cpuPercent =
            if (now == null || prev == null) {
                null
            } else {
                DeviceMetricsSampler.cpuUsagePercent(prev, now)
            }
        if (now != null) deviceCpuPrev = now
        return DeviceSnapshot(
            cpuPercent = cpuPercent,
            gpuPercent = DeviceMetricsSampler.readGpuBusyPercent(),
            ram = DeviceMetricsSampler.readRamReading(activity),
            fps = deviceFpsMonitor?.fps() ?: 0.0,
        )
    }

    private companion object {
        /** MB en un GB (1024 MB, paridad con el sampler del HOST). */
        const val MB_PER_GB = 1024.0
    }
}

/** Búsqueda tipada del gauge por id (evita el cast repetido en la clase). */
private fun gaugeOf(
    activity: AppCompatActivity,
    id: Int,
): GaugeView = activity.findViewById(id)
