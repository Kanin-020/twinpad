package com.twinpad.android.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
 * dibujo directamente. CR-085 v2: el anillo lleva COLOR POR GAUGE vía el
 * atributo XML `ringColor` (GPU azul, CPU magenta, RAM verde — los anillos
 * dejan de ser grises): la pista de fondo es el mismo color atenuado y el
 * dato ausente sigue en gris neutrinal con el valor «—».
 */
class GaugeView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        /** CR-085 v2: color del anillo del gauge (arco y pista atenuada);
         * viene del XML por gauge y por defecto es el acento violeta. */
        private var ringColor: Int = ContextCompat.getColor(context, R.color.metrics_accent)

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
                color = dimmed(ringColor)
            }

        private val ringProgressPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ringColor
            }

        private val valuePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.design_cloud_white)
                textAlign = Paint.Align.CENTER
            }

        /** diámetro disponible para el texto dentro del anillo, en px. */
        private var leadingWidth: Float = 0f

        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.design_misted_slate)
                textAlign = Paint.Align.CENTER
            }

        private val arcBounds = RectF()

        init {
            context.theme
                .obtainStyledAttributes(attrs, R.styleable.GaugeView, defStyleAttr, 0)
                .apply {
                    try {
                        ringColor = getColor(R.styleable.GaugeView_ringColor, ringColor)
                    } finally {
                        recycle()
                    }
                }
            ringBackgroundPaint.color = dimmed(ringColor)
            ringProgressPaint.color = ringColor
        }

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
            // anillo exterior interno (recto menos el grosor del trazo).
            val cx = w / 2f
            val cy = h / CENTER_RATIO
            val radius = minOf(cx, cy) - ringProgressPaint.strokeWidth
            leadingWidth = radius * 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / CENTER_RATIO
            val radius = minOf(cx, cy) - ringProgressPaint.strokeWidth
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

            // CR-085 v2: pista del color del gauge cuando hay dato, gris
            // neutrinal cuando no lo hay («sin dato» sigue atenuado).
            ringBackgroundPaint.color =
                if (hasValue) {
                    dimmed(ringColor)
                } else {
                    RING_INACTIVE_COLOR
                }
            ringProgressPaint.color = ringColor
            canvas.drawArc(arcBounds, 0f, SWEEP, false, ringBackgroundPaint)
            if (hasValue) {
                canvas.drawArc(arcBounds, START_ANGLE, progress * SWEEP, false, ringProgressPaint)
            }

            // CR-086: el valor nunca sale del anillo — si la cadena no cabe
            // con el tamaño nominal, se escala hacia abajo (por mínimo 0.5×).
            val displayedValue = valueText.ifEmpty { "—" }
            fitTextSize(textToMeasure = displayedValue)
            canvas.drawText(
                displayedValue,
                cx,
                cy + valuePaint.textSize * VALUE_BASELINE_RATIO,
                valuePaint,
            )
            canvas.drawText(label, cx, height - labelPaint.textSize * LABEL_BASELINE_RATIO, labelPaint)
        }

        /** CR-086: ajusta el size del valor sólo si la cadena no cabe en el
         * diámetro del anillo (ancho del arco menos el trazo). */
        private fun fitTextSize(textToMeasure: String) {
            if (textToMeasure.isEmpty() || leadingWidth <= 0f) {
                return
            }
            var textSize = valuePaint.textSize
            val minTextSize = textSize * FIT_TEXT_MIN_RATIO
            while (textSize > minTextSize) {
                valuePaint.textSize = textSize
                if (valuePaint.measureText(textToMeasure)
                    <= leadingWidth * FIT_TEXT_CAPTURE_RATIO
                ) {
                    return
                }
                textSize *= FIT_TEXT_DECAY_RATIO
            }
            valuePaint.textSize = textSize
        }

        /** CR-085 v2: color del gauge atenuado para la pista del anillo. */
        private fun dimmed(color: Int): Int {
            return Color.argb(
                TRACK_ALPHA,
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        }

        private companion object {
            /** La referencia arranca el arco arriba y avanza en sentido horario. */
            const val START_ANGLE = -90f
            const val SWEEP = 360f

            /** Color de fondo de los anillos cuando el gauge no tiene dato.
             * CR-077: Quiet Slate de DESIGN.md atenuado sobre el Void Canvas. */
            const val RING_INACTIVE_COLOR = 0x558F94A6.toInt()

            /** Alpha de la pista del anillo (CR-085 v2: 20 % del color). */
            const val TRACK_ALPHA = 0x33

            /** Métricas del dibujo (proporciones del ancho del gauge). */
            const val STROKE_WIDTH_RATIO = 0.08f
            const val STROKE_MIN_DP = 4f
            const val STROKE_MAX_DP = 8f

            /** CR-085 v2: letra DENTRO del anillo más pequeña (0.2 → 0.16
             * del ancho en el valor, y etiqueta 10 → 9 dp). */
            const val VALUE_TEXT_RATIO = 0.16f
            const val LABEL_TEXT_DP = 9f
            const val CENTER_RATIO = 2.2f
            const val VALUE_BASELINE_RATIO = 0.35f
            const val LABEL_BASELINE_RATIO = 0.3f

            /** CR-086: ratio de captura del anillo (el texto cabe si cabe en
             * éste). */
            const val FIT_TEXT_CAPTURE_RATIO = 0.9f

            /** CR-086: mínimo de la escala del texto en pts. */
            const val FIT_TEXT_MIN_RATIO = 0.5f

            /** CR-086: reducción del size entre intentos de ajuste. */
            const val FIT_TEXT_DECAY_RATIO = 0.8f
        }
    }
