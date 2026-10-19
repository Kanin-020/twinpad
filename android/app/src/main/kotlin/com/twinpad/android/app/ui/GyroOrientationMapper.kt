package com.twinpad.android.app.ui

import kotlin.math.roundToInt

private const val RADIANS_PER_TURN = 2f * Math.PI.toFloat()

/** Converts Android azimuth/pitch/roll into Twinpad yaw/pitch/roll after screen rotation is remapped. */
internal object GyroOrientationMapper {
    fun toScreenTurns(
        angles: FloatArray,
        scale: Float,
    ): List<Int> =
        listOf(
            // Android reports azimuth, pitch, roll; remapOrientationMatrix already makes them screen-relative.
            radiansToTurns(angles[0], scale),
            radiansToTurns(angles[1], scale),
            radiansToTurns(angles[2], scale),
        )

    private fun radiansToTurns(
        radians: Float,
        scale: Float,
    ): Int =
        (radians / RADIANS_PER_TURN * scale)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
