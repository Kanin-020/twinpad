package com.twinpad.android.app.ui

import kotlin.math.roundToInt

/** Converts Android orientation radians into normalized screen-relative turns for the optional monitor. */
internal object GyroOrientationMapper {
    private const val SENSOR_AXIS_COUNT = 3
    private const val RADIANS_PER_TURN = 2f * Math.PI.toFloat()
    private const val MONITOR_TURN_SCALE = 32_767f

    fun toScreenTurns(angles: FloatArray): List<Int> {
        if (angles.size < SENSOR_AXIS_COUNT) return emptyList()
        return angles.take(SENSOR_AXIS_COUNT).map { radians ->
            (radians / RADIANS_PER_TURN * MONITOR_TURN_SCALE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }
}
