package com.twinpad.android.app.ui

import kotlin.math.atan2
import kotlin.math.roundToInt

private const val GYRO_SENSITIVITY_DEFAULT = 1.0f
private const val GYRO_SENSITIVITY_MIN = 0.25f
private const val GYRO_SENSITIVITY_MAX = 2.0f
private const val GYRO_CALIBRATION_SAMPLES = 30
private const val GYRO_ZERO_DEADBAND = 0.005f
private const val GYRO_REFERENCE_VECTOR_SIZE = 3
private const val FULL_TURN_INT16_SCALE = 32_767f
private const val DEGREES_PER_RADIAN = 180f
private const val RADIANS_PER_TURN = 2f * Math.PI.toFloat()

/** Calibrates screen-relative yaw/pitch/roll and publishes changed gamepad gyro state. */
internal class ProfileGyroController(
    private val onGyroChanged: (List<Int>) -> Unit,
) {
    private val calibration = mutableListOf<FloatArray>()
    private var reference = FloatArray(GYRO_REFERENCE_VECTOR_SIZE)
    private var calibrated = false
    private var sensitivity = GYRO_SENSITIVITY_DEFAULT
    private var lastReport: List<Int>? = null

    fun update(orientationTurns: List<Int>) {
        if (orientationTurns.size != GYRO_REFERENCE_VECTOR_SIZE) return
        val radians =
            FloatArray(GYRO_REFERENCE_VECTOR_SIZE) { axis ->
                orientationTurns[axis] / FULL_TURN_INT16_SCALE *
                    FULL_TURN_DEGREES * Math.PI.toFloat() / DEGREES_PER_RADIAN
            }
        if (!calibrated) {
            calibration += radians
            if (calibration.size >= GYRO_CALIBRATION_SAMPLES) calibrateReference()
            return
        }
        val adjusted =
            List(GYRO_REFERENCE_VECTOR_SIZE) { axis ->
                val delta = wrapRadians(radians[axis] - reference[axis]) * sensitivity
                if (kotlin.math.abs(delta) < GYRO_ZERO_DEADBAND) {
                    0
                } else {
                    (delta / RADIANS_PER_TURN * FULL_TURN_INT16_SCALE).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                }
            }
        if (adjusted == lastReport) return
        lastReport = adjusted
        onGyroChanged(adjusted)
    }

    fun setSensitivity(value: Float) {
        sensitivity = value.coerceIn(GYRO_SENSITIVITY_MIN, GYRO_SENSITIVITY_MAX)
    }

    fun recalibrate() {
        calibration.clear()
        calibrated = false
    }

    private fun calibrateReference() {
        for (axis in 0 until GYRO_REFERENCE_VECTOR_SIZE) {
            val sine = calibration.sumOf { kotlin.math.sin(it[axis].toDouble()) }
            val cosine = calibration.sumOf { kotlin.math.cos(it[axis].toDouble()) }
            reference[axis] = atan2(sine, cosine).toFloat()
        }
        calibration.clear()
        calibrated = true
    }

    private fun wrapRadians(angle: Float): Float =
        ((angle + Math.PI.toFloat()) % RADIANS_PER_TURN + RADIANS_PER_TURN) % RADIANS_PER_TURN - Math.PI.toFloat()

    private companion object {
        const val FULL_TURN_DEGREES = 360f
    }
}
