package com.twinpad.android.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.twinpad.android.app.R

/** Live visual diagnostic for the screen-relative orientation sent in input_report. */
class GyroMonitorView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val framePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE620252B.toInt()
                style = Paint.Style.FILL
            }
        private val borderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFB8C7D9.toInt()
                style = Paint.Style.STROKE
                strokeWidth = dp(1.5f)
            }
        private val gridPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x668E9AAA
                strokeWidth = dp(1f)
            }
        private val yawPaint = axisPaint(0xFF65D681.toInt())
        private val pitchPaint = axisPaint(0xFFFF5B5B.toInt())
        private val rollPaint = axisPaint(0xFF64A9FF.toInt())
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = dp(11f)
            }
        private val orientationTurns = IntArray(3)

        internal fun orientationForTest(): IntArray = orientationTurns.copyOf()

        internal fun axisColorsForTest(): IntArray = intArrayOf(yawPaint.color, pitchPaint.color, rollPaint.color)

        fun setOrientationTurns(
            yaw: Int,
            pitch: Int,
            roll: Int,
        ) {
            orientationTurns[0] = yaw
            orientationTurns[1] = pitch
            orientationTurns[2] = roll
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bounds, dp(CORNER_RADIUS_DP), dp(CORNER_RADIUS_DP), framePaint)
            canvas.drawRoundRect(bounds, dp(CORNER_RADIUS_DP), dp(CORNER_RADIUS_DP), borderPaint)

            val padding = dp(GRAPH_PADDING_DP)
            val graphTop = dp(GRAPH_TOP_DP)
            val graphBottom = height - dp(GRAPH_BOTTOM_MARGIN_DP)
            val centerX = width / 2f
            val centerY = (graphTop + graphBottom) / 2f
            canvas.drawLine(padding, centerY, width - padding, centerY, gridPaint)
            canvas.drawLine(centerX, graphTop, centerX, graphBottom, gridPaint)
            canvas.drawLine(padding, graphTop, padding, graphBottom, gridPaint)
            canvas.drawLine(width - padding, graphTop, width - padding, graphBottom, gridPaint)
            canvas.drawText(context.getString(R.string.gyro_monitor_title), padding, dp(TITLE_BASELINE_DP), textPaint)

            val extentX = width / 2f - padding - dp(GRAPH_EDGE_INSET_DP)
            val extentY = (graphBottom - graphTop) / 2f - dp(GRAPH_EDGE_INSET_DP)
            val angles = FloatArray(AXIS_COUNT) { index -> orientationTurns[index] / INT16_TURNS_SCALE * TWO_PI }
            val startX = centerX
            val startY = centerY
            val endPoints =
                arrayOf(
                    centerX + kotlin.math.cos(angles[PITCH_AXIS]) * extentX,
                    centerY - kotlin.math.sin(angles[PITCH_AXIS]) * extentY,
                    centerX + kotlin.math.cos(angles[YAW_AXIS]) * extentX,
                    centerY - kotlin.math.sin(angles[YAW_AXIS]) * extentY,
                    centerX + kotlin.math.cos(angles[ROLL_AXIS]) * extentX,
                    centerY - kotlin.math.sin(angles[ROLL_AXIS]) * extentY,
                )
            canvas.drawLine(startX, startY, endPoints[YAW_END_X], endPoints[YAW_END_Y], yawPaint)
            canvas.drawLine(startX, startY, endPoints[PITCH_END_X], endPoints[PITCH_END_Y], pitchPaint)
            canvas.drawLine(startX, startY, endPoints[ROLL_END_X], endPoints[ROLL_END_Y], rollPaint)
            canvas.drawCircle(centerX, centerY, dp(CENTER_MARKER_RADIUS_DP), borderPaint)
            val axisLabels =
                arrayOf(
                    context.getString(R.string.gyro_axis_pitch),
                    context.getString(R.string.gyro_axis_yaw),
                    context.getString(R.string.gyro_axis_roll),
                )
            val axisPaints = listOf(pitchPaint, yawPaint, rollPaint)
            for (axis in axisLabels.indices) {
                val coordinateOffset = axis * COORDINATE_COUNT
                val x =
                    (endPoints[coordinateOffset + X_COORDINATE] + dp(LABEL_OFFSET_DP))
                        .coerceIn(padding, width - dp(LABEL_RIGHT_MARGIN_DP))
                val y =
                    (endPoints[coordinateOffset + Y_COORDINATE] - dp(LABEL_OFFSET_DP))
                        .coerceIn(graphTop + dp(LABEL_TOP_INSET_DP), graphBottom - dp(LABEL_BOTTOM_INSET_DP))
                canvas.drawText(axisLabels[axis], x, y, axisPaints[axis])
            }

            val valueText =
                context.getString(
                    R.string.gyro_values,
                    orientationTurns[0],
                    orientationTurns[1],
                    orientationTurns[2],
                )
            canvas.drawText(valueText, padding, height - dp(VALUE_BASELINE_MARGIN_DP), textPaint)
        }

        private fun axisPaint(color: Int) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = dp(AXIS_STROKE_WIDTH_DP)
                strokeCap = Paint.Cap.ROUND
            }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density

        private companion object {
            const val AXIS_COUNT = 3
            const val YAW_AXIS = 0
            const val PITCH_AXIS = 1
            const val ROLL_AXIS = 2
            const val COORDINATE_COUNT = 2
            const val X_COORDINATE = 0
            const val Y_COORDINATE = 1
            const val YAW_END_X = 2
            const val YAW_END_Y = 3
            const val PITCH_END_X = 0
            const val PITCH_END_Y = 1
            const val ROLL_END_X = 4
            const val ROLL_END_Y = 5
            const val INT16_TURNS_SCALE = 32768f
            const val CORNER_RADIUS_DP = 8f
            const val GRAPH_PADDING_DP = 10f
            const val GRAPH_TOP_DP = 30f
            const val GRAPH_BOTTOM_MARGIN_DP = 37f
            const val TITLE_BASELINE_DP = 19f
            const val GRAPH_EDGE_INSET_DP = 4f
            const val CENTER_MARKER_RADIUS_DP = 3f
            const val LABEL_OFFSET_DP = 4f
            const val LABEL_RIGHT_MARGIN_DP = 36f
            const val LABEL_TOP_INSET_DP = 10f
            const val LABEL_BOTTOM_INSET_DP = 2f
            const val VALUE_BASELINE_MARGIN_DP = 12f
            const val AXIS_STROKE_WIDTH_DP = 3f
            const val TWO_PI = (2.0 * Math.PI).toFloat()
        }
    }
