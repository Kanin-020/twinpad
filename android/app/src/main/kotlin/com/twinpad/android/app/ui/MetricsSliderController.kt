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
 * Modos: WAITING (vista fullscreen antes del stream), SHEET (bottom sheet
 * sobre el video, plegada al asa) y HIDDEN (slider desactivado).
 */
internal class MetricsSliderController(
    private val activity: Activity,
) {
    private enum class SheetMode { SHEET, HIDDEN }

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
                    if (mode == SheetMode.SHEET) {
                        applySheetAlpha(slideOffset)
                    }
                }
            },
        )
    }

    /** Transparente a 0 % (plegada) y sólido a 100 % (expandida). */
    private fun applySheetAlpha(slideOffset: Float) {
        val fraction = slideOffset.coerceIn(0f, 1f)
        sheet.background?.mutate()?.alpha = (fraction * OPAQUE_ALPHA).toInt()
    }

    /** `true` en modo SHEET: el tick pinta los gauges. */
    val sheetActive: Boolean
        get() = mode == SheetMode.SHEET

    /** Idempotente: solo re-aplica cuando cambia el estado. */
    fun setStreaming(
        streaming: Boolean,
        sliderEnabled: Boolean,
    ) {
        // Sin stream la hoja es la misma que con stream: solo cambia el video de fondo.
        val target =
            when {
                !streaming || sliderEnabled -> SheetMode.SHEET
                else -> SheetMode.HIDDEN
            }
        if (target == mode) {
            return
        }
        mode = target
        this.streaming = streaming
        applyCurrent()
    }

    private var streaming = false

    private fun applyCurrent() {
        // Sin transmisión, el fondo es el tapete de mesa con el logo; con
        // transmisión la hoja queda transparente sobre el video.
        slider.background = null
        when (mode) {
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
