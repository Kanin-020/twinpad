package com.twinpad.android.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.twinpad.android.app.R

/**
 * CR-076 v2: gauge circular estilo handheld (referencia `reference/AYN-Thor-18.jpg`):
 * anillo de progreso con el valor al centro y la etiqueta debajo. La medición
 * del HOST llega por `host_metrics` del server (CR-076 v2); esta View solo
 * pinta progreso 0..1, texto y etiqueta. Cambios de contenido invalidan el
 * dibujo directamente.
 */
class GaugeView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** Progreso del arco en 0..1 (valores fuera de rango quedan topeados). */
        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        /** Texto del valor (ya formateado por el caller: «37%», «4,2 GB», «60»). */
        var valueText: String = ""
            set(value) {
                field = value
                invalidate()
            }

        /** Etiqueta corta bajo el valor (CPU, GPU, RAM, FPS). */
        var label: String = ""
            set(value) {
                field = value
                invalidate()
            }

        /** Sin dato: anillo atenuado y valor «—» (el sampler decide cuándo). */
        var hasValue: Boolean = true
            set(value) {
                field = value
                invalidate()
            }

        private val ringBackgroundPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ContextCompat.getColor(context, R.color.design_slate_edge)
            }

        private val ringProgressPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ContextCompat.getColor(context, R.color.metrics_accent)
            }

        private val valuePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.design_cloud_white)
                textAlign = Paint.Align.CENTER
            }

        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.design_misted_slate)
                textAlign = Paint.Align.CENTER
            }

        private val arcBounds = RectF()

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            val density = resources.displayMetrics.density
            val stroke =
                (w.coerceAtLeast(h) * STROKE_WIDTH_RATIO)
                    .coerceIn(STROKE_MIN_DP * density, STROKE_MAX_DP * density)
            ringBackgroundPaint.strokeWidth = stroke
            ringProgressPaint.strokeWidth = stroke
            valuePaint.textSize = w * VALUE_TEXT_RATIO
            labelPaint.textSize = density * LABEL_TEXT_DP
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / CENTER_RATIO
            val radius = minOf(cx, cy) - ringProgressPaint.strokeWidth
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

            val ringColor = if (hasValue) ringProgressPaint.color else RING_INACTIVE_COLOR
            ringProgressPaint.color = ringColor
            canvas.drawArc(arcBounds, 0f, SWEEP, false, ringBackgroundPaint)
            if (hasValue) {
                canvas.drawArc(arcBounds, START_ANGLE, progress * SWEEP, false, ringProgressPaint)
            }

            canvas.drawText(valueText.ifEmpty { "—" }, cx, cy + valuePaint.textSize * VALUE_BASELINE_RATIO, valuePaint)
            canvas.drawText(label, cx, height - labelPaint.textSize * LABEL_BASELINE_RATIO, labelPaint)
        }

        private companion object {
            /** La referencia arranca el arco arriba y avanza en sentido horario. */
            const val START_ANGLE = -90f
            const val SWEEP = 360f

            /** Color de fondo de los anillos cuando el gauge no tiene dato.
             * CR-077: Quiet Slate de DESIGN.md atenudado sobre el Void Canvas. */
            const val RING_INACTIVE_COLOR = 0x558F94A6.toInt()

            /** Métricas del dibujo (proporciones del ancho del gauge). */
            const val STROKE_WIDTH_RATIO = 0.08f
            const val STROKE_MIN_DP = 4f
            const val STROKE_MAX_DP = 8f
            const val VALUE_TEXT_RATIO = 0.2f
            const val LABEL_TEXT_DP = 10f
            const val CENTER_RATIO = 2.2f
            const val VALUE_BASELINE_RATIO = 0.35f
            const val LABEL_BASELINE_RATIO = 0.3f
        }
    }
