package com.twinpad.android.app.ui

import com.twinpad.android.app.wire.MotionInput
import com.twinpad.android.app.wire.ProtocolConstants
import kotlin.math.max

private const val SENSOR_AXIS_COUNT = 3
private const val NANOS_PER_MICROSECOND = 1_000L

/** Maps native accelerometer axes to the AndroidDSU landscape reference frame (accX=y, accY=-z, accZ=x). */
internal fun remapAccelerationVector(values: FloatArray): FloatArray {
    return floatArrayOf(values[1], -values[2], values[0])
}

/** Maps native gyroscope axes to the AndroidDSU landscape reference frame (P=-y, Y=-z, R=x). */
internal fun remapGyroscopeVector(values: FloatArray): FloatArray {
    return floatArrayOf(-values[1], -values[2], values[0])
}

/** Combines the latest Android accelerometer and gyroscope events into one DSU motion sample. */
internal class ProfileGyroController(
    private val onMotionChanged: (MotionInput?) -> Unit,
) {
    private var acceleration: List<Float>? = null
    private var accelerationTimestampNanos = 0L
    private var angularVelocity: List<Float>? = null
    private var angularVelocityTimestampNanos = 0L
    private var lastReportTimestampNanos = 0L

    fun updateAcceleration(
        values: FloatArray,
        timestampNanos: Long,
    ) {
        if (
            values.size < SENSOR_AXIS_COUNT || timestampNanos <= 0L ||
            values.take(SENSOR_AXIS_COUNT).any { !it.isFinite() }
        ) {
            return
        }
        acceleration = remapAccelerationVector(values.take(SENSOR_AXIS_COUNT).toFloatArray()).toList()
        accelerationTimestampNanos = timestampNanos
        publishLatestIfDue(timestampNanos)
    }

    fun updateGyroscope(
        values: FloatArray,
        timestampNanos: Long,
    ) {
        if (
            values.size < SENSOR_AXIS_COUNT || timestampNanos <= 0L ||
            values.take(SENSOR_AXIS_COUNT).any { !it.isFinite() }
        ) {
            return
        }
        angularVelocity = remapGyroscopeVector(values.take(SENSOR_AXIS_COUNT).toFloatArray()).toList()
        angularVelocityTimestampNanos = timestampNanos
        publishLatestIfDue(timestampNanos)
    }

    fun reset() {
        acceleration = null
        accelerationTimestampNanos = 0L
        angularVelocity = null
        angularVelocityTimestampNanos = 0L
        lastReportTimestampNanos = 0L
        onMotionChanged(null)
    }

    private fun publishLatestIfDue(eventTimestampNanos: Long) {
        if (
            lastReportTimestampNanos != 0L &&
            eventTimestampNanos - lastReportTimestampNanos <
            ProtocolConstants.SENSOR_REPORT_INTERVAL_US * NANOS_PER_MICROSECOND
        ) {
            return
        }
        publishLatest()
        if (acceleration != null && angularVelocity != null) {
            lastReportTimestampNanos = eventTimestampNanos
        }
    }

    private fun publishLatest() {
        val accel = acceleration
        val gyro = angularVelocity
        if (accel == null || gyro == null) {
            onMotionChanged(null)
            return
        }
        onMotionChanged(
            MotionInput(
                timestampUs =
                    max(accelerationTimestampNanos, angularVelocityTimestampNanos) / NANOS_PER_MICROSECOND,
                accel = accel,
                gyro = gyro,
            ),
        )
    }
}
