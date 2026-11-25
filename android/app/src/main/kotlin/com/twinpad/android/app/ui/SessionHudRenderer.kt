package com.twinpad.android.app.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.twinpad.android.app.R
import com.twinpad.android.app.stream.StreamStats
import com.twinpad.android.app.wire.VideoStageStatsCodec
import com.twinpad.android.app.wire.VideoStageStatsMessage

/**
 * HUD de la sesión: chip de fps/kbps (CR-112) y bloque «Métricas avanzadas»
 * (CR-054/CR-075). Extraído de `PairingActivity` (que superaba el umbral de
 * tamaño de detekt) sin cambiar el comportamiento: el chip sigue al interruptor
 * «Mostrar fps» y las líneas de etapas/detalles al de «Métricas avanzadas».
 */
internal class SessionHudRenderer(
    private val context: Context,
    private val findView: (Int) -> View,
    private val stats: StreamStats,
    private val stageStats: () -> VideoStageStatsMessage?,
    private val mediaTransport: () -> Boolean?,
) {
    fun render(
        hudVisible: Boolean,
        advancedVisible: Boolean,
    ) {
        renderChip(hudVisible)
        renderStageLines(advancedVisible)
        renderStageDetails(advancedVisible)
    }

    private fun renderChip(hudVisible: Boolean) {
        val chip = findView(R.id.hud_chip) as TextView
        if (!hudVisible) {
            chip.isVisible = false
            return
        }
        stats.refresh()
        chip.text =
            context.getString(
                R.string.hud_chip_text,
                "%.0f fps".format(stats.fps),
                "%.0f fps".format(stats.receiveFps),
                "%.1f".format(stats.kbps / KBPS_PER_MBPS),
            )
        chip.isVisible = true
    }

    private fun renderStageLines(advancedVisible: Boolean) {
        val stageLine = findView(R.id.hud_stage) as TextView
        val formatted = stageStats()?.let(VideoStageStatsCodec::format)
        if (!advancedVisible || formatted == null) {
            stageLine.isVisible = false
        } else {
            stageLine.text = context.getString(R.string.hud_stage_title) + "\n" + formatted
            stageLine.isVisible = true
        }
    }

    /** Segunda línea con SOLO lo conocido: backend del encoder (hw), transporte
     * media (UDP/TCP) y las resoluciones de entrada/salida del Plugin. */
    private fun renderStageDetails(advancedVisible: Boolean) {
        val details = findView(R.id.hud_stage_details) as TextView
        if (!advancedVisible) {
            details.isVisible = false
            return
        }
        val transport = mediaTransport()?.let { if (it) "UDP" else "TCP" }
        val detailsText = stageStats()?.let { VideoStageStatsCodec.formatDetails(it, transport) }
        if (detailsText == null) {
            details.isVisible = false
        } else {
            details.text = detailsText
            details.isVisible = true
        }
    }

    private companion object {
        /** CR-112: el chip muestra Mbps con un decimal. */
        const val KBPS_PER_MBPS = 1000.0
    }
}
