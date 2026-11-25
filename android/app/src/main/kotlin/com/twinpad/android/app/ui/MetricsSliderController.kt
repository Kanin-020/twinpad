package com.twinpad.android.app.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.twinpad.android.app.R

/**
 * CR-107: barra de rendimiento como `BottomSheetBehavior` de Material.
 * Modos: WAITING (monitor abierto antes del stream, sobre el tapete de
 * mesa), SHEET (bottom sheet sobre el video, plegada al asa) y HIDDEN
 * (slider desactivado).
 */
internal class MetricsSliderController(
    private val activity: Activity,
) {
    private enum class SheetMode { WAITING, SHEET, HIDDEN }

    private val slider: View = activity.findViewById(R.id.metrics_slider)
    private val sheet: View = activity.findViewById(R.id.metrics_sheet)
    private val content: ViewGroup = activity.findViewById(R.id.metrics_content)
    private val handle: View = activity.findViewById(R.id.metrics_handle)

    private val behavior: BottomSheetBehavior<View> =
        BottomSheetBehavior.from(sheet).apply {
            isHideable = true
            isDraggable = true
            state = BottomSheetBehavior.STATE_HIDDEN
        }

    private var mode = SheetMode.HIDDEN

    init {
        behavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(
                    bottomSheet: View,
                    newState: Int,
                ) = Unit

                override fun onSlide(
                    bottomSheet: View,
                    slideOffset: Float,
                ) {
                    // CR-112: el fundido del panel acompaña al arrastre en
                    // cualquier modo visible (antes solo en SHEET y el monitor
                    // de espera se quedaba opaco al plegarlo).
                    if (mode != SheetMode.HIDDEN) {
                        applySheetAlpha(slideOffset)
                    }
                }
            },
        )
    }

    /** Transparente a 0 % (plegada, se ve el tapete/video detrás) y sólido a
     * 100 % (expandida): el mismo efecto en espera y sobre el stream. */
    private fun applySheetAlpha(slideOffset: Float) {
        val fraction = slideOffset.coerceIn(0f, 1f)
        sheet.background?.mutate()?.alpha = (fraction * OPAQUE_ALPHA).toInt()
    }

    /** `true` con el monitor visible (WAITING o SHEET): el tick pinta los gauges. */
    val sheetActive: Boolean
        get() = mode != SheetMode.HIDDEN

    /** Idempotente: solo re-aplica cuando cambia el estado. */
    fun setStreaming(
        streaming: Boolean,
        sliderEnabled: Boolean,
    ) {
        // Sin transmisión el monitor se abre (WAITING); con transmisión es
        // la hoja arrastrable sobre el video, o HIDDEN si el slider está apagado.
        val target =
            when {
                !streaming -> SheetMode.WAITING
                sliderEnabled -> SheetMode.SHEET
                else -> SheetMode.HIDDEN
            }
        if (target == mode) {
            return
        }
        mode = target
        applyCurrent()
    }

    private fun applyCurrent() {
        slider.background = null
        when (mode) {
            // Sin sesión: monitor abierto sobre el tapete de mesa, NO sobre un
            // color plano (eso era la «pantalla negra»). Se abre y se cierra a
            // voluntad: se queda en COLLAPSED (asa visible) en vez de HIDDEN,
            // porque escondido y sin asa ya no había forma de volver a abrirlo.
            SheetMode.WAITING -> {
                slider.isVisible = true
                setSheetFullLayout(Gravity.BOTTOM)
                sheet.background =
                    ContextCompat.getDrawable(activity, R.drawable.bg_table)?.mutate()?.apply {
                        alpha = OPAQUE_ALPHA
                    }
                content.background = null
                handle.isVisible = true
                behavior.isHideable = false
                behavior.isDraggable = true
                behavior.peekHeight = peekHeightPx()
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                applySheetAlpha(1f)
            }
            SheetMode.SHEET -> {
                slider.isVisible = true
                setSheetFullLayout(Gravity.BOTTOM)
                sheet.background = ContextCompat.getDrawable(activity, R.drawable.bg_metrics_sheet)
                content.background = null
                handle.isVisible = true
                behavior.isHideable = false
                behavior.peekHeight = peekHeightPx()
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                applySheetAlpha(0f)
            }
            SheetMode.HIDDEN -> {
                slider.isVisible = false
                behavior.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }
    }

    /** Alto plegado: asa (24dp) + márgenes (4dp arriba, 10dp abajo). */
    private fun peekHeightPx(): Int = (PEEK_DP * activity.resources.displayMetrics.density).toInt()

    /**
     * Modifica los LayoutParams existentes: reemplazarlos borraba el
     * `layout_behavior` de la hoja y el BottomSheetBehavior dejaba de arrastrar.
     */
    private fun setSheetFullLayout(gravity: Int) {
        val params = sheet.layoutParams as CoordinatorLayout.LayoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = gravity
        sheet.requestLayout()
    }

    private companion object {
        const val PEEK_DP = 38f
        const val OPAQUE_ALPHA = 255
    }
}
