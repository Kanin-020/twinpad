package com.twinpad.android.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST-0156 — verifies REQ-006/SPEC-010 (CR-076): lógica pura de medición
 * del dispositivo para los gauges de la ventana de espera (CPU/GPU/RAM/FPS).
 * Los adaptadores Android (`/proc`, sysfs, ActivityManager) no se mockean:
 * se prueban SOLO las funciones puras, tal como exige test-strategy §2 para
 * JVM sin dispositivo.
 */
class DeviceMetricsSamplerTest {
    // ---- CR-076: CPU por delta de /proc/stat (busca busy/total). ----    @Test
    fun cpu_stat_line_parses_busy_and_total() {
        val first =
            DeviceMetricsSampler.parseCpuStat(
                "cpu 1000 0 1000 7000 1000 0 1000 0 0 0",
            )!!
        val second =
            DeviceMetricsSampler.parseCpuStat(
                "cpu 1500 0 1200 7200 1000 0 1100 0 0 0",
            )!!

        // busy = total - (idle idx3 + iowait idx4): 1000+0+1000+1000.
        assertEquals(3_000L, first.busyJiffies)
        assertEquals(11_000L, first.totalJiffies)

        // Δbusy = 800; Δtotal = 1_000 → 80 %.
        assertEquals(80, DeviceMetricsSampler.cpuUsagePercent(first, second))
    }

    @Test
    fun cpu_idle_machine_reports_zero_percent() {
        val first = DeviceMetricsSampler.parseCpuStat("cpu 0 0 0 1000 0 0 0 0")!!
        val second = DeviceMetricsSampler.parseCpuStat("cpu 0 0 0 2000 0 0 0 0")!!
        assertEquals(0, DeviceMetricsSampler.cpuUsagePercent(first, second))
    }

    @Test
    fun cpu_line_with_negative_delta_or_bad_tokens_is_rejected() {
        assertNull(DeviceMetricsSampler.parseCpuStat("cpu2 1 2 3 4 5"))
        assertNull(DeviceMetricsSampler.parseCpuStat("cpu 1 2 3"))
        assertNull(DeviceMetricsSampler.parseCpuStat("cpu 1 2 x 4 5"))
        val first = DeviceMetricsSampler.parseCpuStat("cpu 100 0 0 100 0 0 0 0")!!
        val second = DeviceMetricsSampler.parseCpuStat("cpu 100 0 0 100 0 0 0 0")!!
        assertNull(DeviceMetricsSampler.cpuUsagePercent(first, second))
    }

    // ---- CR-076: GPU del driver (Adreno kgsl «37 %»). ----

    @Test
    fun gpu_busy_parses_kgsl_format_and_rejects_noise() {
        assertEquals(37, DeviceMetricsSampler.parseGpuBusy("37 %"))
        assertEquals(0, DeviceMetricsSampler.parseGpuBusy("0%"))
        assertEquals(100, DeviceMetricsSampler.parseGpuBusy("100 %"))
        assertNull(DeviceMetricsSampler.parseGpuBusy("busy"))
        assertNull(DeviceMetricsSampler.parseGpuBusy("-5 %"))
        assertNull(DeviceMetricsSampler.parseGpuBusy("137 %"))
    }

    // ---- CR-076: RAM % y GB desde bytes. ----

    @Test
    fun ram_percent_and_gb_text() {
        val reading =
            DeviceMetricsSampler.RamReading(
                usedBytes = 4L * GIB_MAX + 512 * MB_MAX,
                totalBytes = 8L * GIB_MAX,
            )
        // 4.5 / 8 → 56 % (floor) y «4.5 GB».
        assertEquals(56, reading.usedPercent)
        assertEquals("4.5 GB", reading.usedGbText)
    }

    @Test
    fun ram_with_zero_total_does_not_divide() {
        val reading = DeviceMetricsSampler.RamReading(usedBytes = 0, totalBytes = 0)
        assertEquals(0, reading.usedPercent)
    }

    // ---- CR-076: FPS con ventana deslizante de ticks. ----

    @Test
    fun fps_window_counts_frames_per_second() {
        val windowNs = 1_000_000_000L
        val sampler = DeviceMetricsSampler.FpsWindow(windowNs)
        // 61 ticks de 60 fps exactos (16.666… ms): primero pasa a regir la ventana.
        var t = 0L
        repeat(61) {
            sampler.addFrameTick(t)
            t += 16_666_667L
        }
        assertEquals(60.0, sampler.fps(), 1.0)
    }

    @Test
    fun fps_window_without_enough_ticks_is_zero() {
        val sampler = DeviceMetricsSampler.FpsWindow()
        sampler.addFrameTick(0L)
        assertEquals(0.0, sampler.fps(), 0.0)
    }

    private companion object {
        const val GIB_MAX = 1024L * 1024L * 1024L
        const val MB_MAX = 1024L * 1024L
    }
}
