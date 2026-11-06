package com.twinpad.android.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.twinpad.android.app.R

/**
 * CR-076 v2: gauge circular estilo handheld (referencia `reference/AYN-Thor-18.jpg`):
 * anillo de progreso con el valor al centro y la etiqueta debajo. La medición
 * del HOST llega por `host_metrics` del server (CR-076 v2); esta View dibuja
 * progreso 0..1, texto y etiqueta. CR-085 v2 asigna color por gauge y CR-086
 * anima suavemente los cambios de progreso y recorre el arco con un brillo
 * continuo de carga mientras el gauge esté visible y tenga dato.
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

        /** Animator vigente, cancelado al llegar una muestra nueva. */
        private var progressAnimator: ValueAnimator? = null

        /** CR-086 v2: animator del brillo de carga (continuo, sin fin). */
        private var chargingAnimator: ValueAnimator? = null

        /** Fase 0..1 del brillo de carga sobre el arco lleno. */
        private var chargingPhase: Float = 0f

        private var currentProgress: Float = 0f

        /** Progreso del arco en 0..1 (valores fuera de rango quedan topeados). */
        var progress: Float
            get() = currentProgress
            set(value) {
                progressAnimator?.cancel()
                progressAnimator = null
                currentProgress = value.coerceIn(0f, 1f)
                invalidate()
            }

        /** Transiciona el arco hasta la nueva muestra en 700 ms. */
        fun animateProgressTo(value: Float) {
            val target = value.coerceIn(0f, 1f)
            progressAnimator?.cancel()
            if (target == progress) {
                progressAnimator = null
                return
            }
            progressAnimator =
                ValueAnimator.ofFloat(progress, target).apply {
                    duration = PROGRESS_ANIMATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        currentProgress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                        invalidate()
                        progressAnimator = animator.takeUnless { it.animatedFraction >= 1f }
                    }
                    start()
                }
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
                updateChargingAnimation()
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

        /** CR-086 v2: brillo de carga que recorre el arco lleno. */
        private val chargingHighlightPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ContextCompat.getColor(context, R.color.design_cloud_white)
                alpha = CHARGING_HIGHLIGHT_ALPHA
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
            chargingHighlightPaint.strokeWidth = stroke
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
                val filledSweep = progress * SWEEP
                canvas.drawArc(arcBounds, START_ANGLE, filledSweep, false, ringProgressPaint)
                // CR-086 v2: brillo de carga continuo sobre lo ya lleno —
                // señal visual de que la muestra no está congelada.
                chargingHighlightSpan(chargingPhase, filledSweep)?.let { span ->
                    canvas.drawArc(
                        arcBounds,
                        START_ANGLE + span.start,
                        span.endInclusive - span.start,
                        false,
                        chargingHighlightPaint,
                    )
                }
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

        /** CR-086 v2: el brillo de carga corre solo con dato y vista visible;
         * así no gasta batería con la hoja oculta y «—» queda quieto. */
        private fun updateChargingAnimation() {
            val visible = isShown && windowVisibility == View.VISIBLE
            if (hasValue && visible) {
                startChargingAnimation()
            } else {
                stopChargingAnimation()
            }
        }

        /** Arranca (idempotente) el barrido continuo del brillo. */
        private fun startChargingAnimation() {
            if (chargingAnimator != null) {
                return
            }
            chargingAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = CHARGING_CYCLE_MS
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener { animator ->
                        chargingPhase = animator.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }

        /** Detiene el brillo y lo deja fuera del arco (fase 0). */
        private fun stopChargingAnimation() {
            chargingAnimator?.cancel()
            chargingAnimator = null
            chargingPhase = 0f
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            updateChargingAnimation()
        }

        override fun onDetachedFromWindow() {
            stopChargingAnimation()
            super.onDetachedFromWindow()
        }

        override fun onVisibilityChanged(
            changedView: View,
            visibility: Int,
        ) {
            super.onVisibilityChanged(changedView, visibility)
            updateChargingAnimation()
        }

        /** CR-086 v2: al ocultarse la ventana (app en segundo plano) el brillo
         * se detiene; al volver se reanuda si sigue habiendo dato. */
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            updateChargingAnimation()
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

            /** Duración de la transición circular (CR-086). */
            const val PROGRESS_ANIMATION_MS = 700L

            /** CR-086 v2: una vuelta del brillo de carga (batería). */
            const val CHARGING_CYCLE_MS = 1800L

            /** CR-086 v2: alpha del brillo (35 % del blanco). */
            const val CHARGING_HIGHLIGHT_ALPHA = 0x59

            /** CR-086: ratio de captura del anillo (el texto cabe si cabe en
             * éste). */
            const val FIT_TEXT_CAPTURE_RATIO = 0.9f

            /** CR-086: mínimo de la escala del texto en pts. */
            const val FIT_TEXT_MIN_RATIO = 0.5f

            /** CR-086: reducción del size entre intentos de ajuste. */
            const val FIT_TEXT_DECAY_RATIO = 0.8f
        }
    }

/** Ancho del brillo de carga (CR-086 v2), en grados. */
private const val CHARGING_HIGHLIGHT_SWEEP = 36f

/** Una vuelta completa del arco, en grados. */
private const val FULL_SWEEP_DEGREES = 360f

/**
 * CR-086 v2: tramo visible del brillo de carga, en grados relativos al
 * arranque del arco. La ventana de [CHARGING_HIGHLIGHT_SWEEP] avanza de 0 a
 * 360 según [phase] (0..1) y solo se dibuja la parte que cae dentro del arco
 * lleno ([filledSweep]); sin solape devuelve `null`. Función pura: la misma
 * entrada produce siempre el mismo tramo.
 */
internal fun chargingHighlightSpan(
    phase: Float,
    filledSweep: Float,
): ClosedFloatingPointRange<Float>? {
    val head = phase.coerceIn(0f, 1f) * FULL_SWEEP_DEGREES
    val start = (head - CHARGING_HIGHLIGHT_SWEEP).coerceAtLeast(0f)
    val end = minOf(head, filledSweep.coerceIn(0f, FULL_SWEEP_DEGREES))
    return if (end > start) start..end else null
}
