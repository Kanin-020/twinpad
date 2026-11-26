package com.twinpad.android.app.ui

import android.app.Activity
import com.twinpad.android.app.R
import com.twinpad.android.app.hid.PhysicalAxes
import com.twinpad.android.app.hid.PhysicalInput
import com.twinpad.android.app.hid.PhysicalKeys

/** SPEC-011 §7: nombre legible de una entrada física en una fila del mapeador. */
internal object GamepadMapperLabels {
    /** Separador de una combinación de botones (CR-123). */
    const val CHORD_SEPARATOR = " + "
    private const val POSITIVE_SIGN = "+"
    private const val NEGATIVE_SIGN = "−"

    fun describe(
        activity: Activity,
        input: PhysicalInput,
    ): String =
        when (input) {
            is PhysicalInput.Key -> keyName(activity, input.keyCode)
            is PhysicalInput.Combo ->
                input.keyCodes.joinToString(CHORD_SEPARATOR) { keyName(activity, it) }
            is PhysicalInput.Axis ->
                activity.getString(
                    R.string.gamepad_mapper_axis,
                    axisName(activity, input.axis),
                    if (input.sign > 0) POSITIVE_SIGN else NEGATIVE_SIGN,
                )
        }

    private fun keyName(
        activity: Activity,
        keyCode: Int,
    ): String = KEY_NAMES[keyCode] ?: activity.getString(R.string.gamepad_mapper_key, keyCode)

    private fun axisName(
        activity: Activity,
        axis: Int,
    ): String = AXIS_NAMES[axis] ?: activity.getString(R.string.gamepad_mapper_axis_name, axis)

    private val KEY_NAMES =
        mapOf(
            PhysicalKeys.BUTTON_A to "A",
            PhysicalKeys.BUTTON_B to "B",
            PhysicalKeys.BUTTON_X to "X",
            PhysicalKeys.BUTTON_Y to "Y",
            PhysicalKeys.BUTTON_L1 to "LB",
            PhysicalKeys.BUTTON_R1 to "RB",
            PhysicalKeys.BUTTON_L2 to "LT",
            PhysicalKeys.BUTTON_R2 to "RT",
            PhysicalKeys.BUTTON_L3 to "L3",
            PhysicalKeys.BUTTON_R3 to "R3",
            PhysicalKeys.BUTTON_START to "Start",
            PhysicalKeys.BUTTON_SELECT to "Select",
            PhysicalKeys.BUTTON_MODE to "Home",
            PhysicalKeys.DPAD_UP to "D-pad ↑",
            PhysicalKeys.DPAD_DOWN to "D-pad ↓",
            PhysicalKeys.DPAD_LEFT to "D-pad ←",
            PhysicalKeys.DPAD_RIGHT to "D-pad →",
        )
    private val AXIS_NAMES =
        mapOf(
            PhysicalAxes.AXIS_X to "X",
            PhysicalAxes.AXIS_Y to "Y",
            PhysicalAxes.AXIS_Z to "Z",
            PhysicalAxes.AXIS_RX to "RX",
            PhysicalAxes.AXIS_RY to "RY",
            PhysicalAxes.AXIS_RZ to "RZ",
            PhysicalAxes.AXIS_HAT_X to "HAT X",
            PhysicalAxes.AXIS_HAT_Y to "HAT Y",
            PhysicalAxes.AXIS_LTRIGGER to "LT",
            PhysicalAxes.AXIS_RTRIGGER to "RT",
            PhysicalAxes.AXIS_THROTTLE to "THROTTLE",
            PhysicalAxes.AXIS_GAS to "GAS",
            PhysicalAxes.AXIS_BRAKE to "BRAKE",
        )
}
