package com.twinpad.android.app.ui

private const val GYRO_AXIS_COUNT = 3

/** Publishes the absolute screen-relative yaw/pitch/roll unchanged to input_report. */
internal class ProfileGyroController(
    private val onGyroChanged: (List<Int>) -> Unit,
) {
    fun update(orientationTurns: List<Int>) {
        if (orientationTurns.size != GYRO_AXIS_COUNT) return
        val absoluteOrientation =
            orientationTurns.map { it.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) }
        onGyroChanged(absoluteOrientation)
    }
}
