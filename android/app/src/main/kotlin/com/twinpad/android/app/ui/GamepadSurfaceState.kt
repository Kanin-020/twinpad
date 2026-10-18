package com.twinpad.android.app.ui

import com.twinpad.android.app.wire.InputReport
import kotlin.math.roundToInt

/**
 * Estado puro de la superficie táctil del gamepad — SPEC-004 §7 paso 1.
 * Mantiene la instantánea de los elementos alimentados por la UI (D-pad,
 * botones, 2 sticks analógicos −1…1, triggers 0…1) y la compone como
 * [InputReport] en rangos de la Signature (§4: sticks −127…127 con centro 0,
 * triggers 0…255). Sin imports de Android: testeable en JVM (ENG §5 P11).
 *
 * El `seq` lo asigna GamepadStateMachine (SPEC-004 R2); aquí va 0.
 */
class GamepadSurfaceState {
    /** Sticks normalizados −1…1 (0 = centro). */
    private var stickLx = 0f
    private var stickLy = 0f
    private var stickRx = 0f
    private var stickRy = 0f

    /** Triggers normalizados 0…1 (0 = reposo). */
    private var triggerL2 = 0f
    private var triggerR2 = 0f

    private var dpad = DpadState.NEUTRAL
    private var buttons = 0
    private var touchX = 0
    private var touchY = 0
    private var touchPressed = false
    private var gyro: List<Int>? = null

    private companion object {
        const val GYRO_AXIS_COUNT = 3
    }

    /** Actualiza las coordenadas normalizadas del touch DRC (0…65535). */
    fun setTouch(
        x: Int,
        y: Int,
        pressed: Boolean,
    ) {
        touchX = x.coerceIn(0, ProtocolRanges.TOUCH_MAX)
        touchY = y.coerceIn(0, ProtocolRanges.TOUCH_MAX)
        touchPressed = pressed
    }

    /** Actualiza orientación absoluta yaw/pitch/roll en vueltas int16. */
    fun setGyro(orientation: List<Int>?) {
        gyro =
            orientation
                ?.takeIf { it.size == GYRO_AXIS_COUNT }
                ?.map { it.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) }
    }

    /** Mueve el stick indicado con ejes normalizados −1…1 (se clampean). */
    fun setStick(
        normalizedX: Float,
        normalizedY: Float,
        isLeft: Boolean,
    ) {
        val x = normalizedX.coerceIn(-1f, 1f)
        val y = normalizedY.coerceIn(-1f, 1f)
        if (isLeft) {
            stickLx = x
            stickLy = y
        } else {
            stickRx = x
            stickRy = y
        }
    }

    /** Fija el trigger indicado con valor normalizado 0…1 (se clampea). */
    fun setTrigger(
        normalizedValue: Float,
        isLeft: Boolean,
    ) {
        val v = normalizedValue.coerceIn(0f, 1f)
        if (isLeft) {
            triggerL2 = v
        } else {
            triggerR2 = v
        }
    }

    /** Fija la dirección del D-pad (0–7, u 8 = neutro, SPEC-004 §4). */
    fun setDpad(direction: Int) {
        dpad = DpadState(direction)
    }

    /** Enciende/apaga los bits de botones (máscara de la Signature §4). */
    fun setButtons(mask: Int) {
        buttons = mask
    }

    /** Instantánea completa en rangos de la Signature (§4). */
    fun snapshot(): InputReport =
        InputReport(
            seq = 0,
            stickLx = (stickLx * ProtocolRanges.STICK_MAX).roundToInt(),
            stickLy = (stickLy * ProtocolRanges.STICK_MAX).roundToInt(),
            stickRx = (stickRx * ProtocolRanges.STICK_MAX).roundToInt(),
            stickRy = (stickRy * ProtocolRanges.STICK_MAX).roundToInt(),
            dpad = dpad.value,
            buttons = buttons,
            triggerL2 = (triggerL2 * ProtocolRanges.TRIGGER_MAX).roundToInt(),
            triggerR2 = (triggerR2 * ProtocolRanges.TRIGGER_MAX).roundToInt(),
            gyro = gyro,
            touchX = touchX,
            touchY = touchY,
            touchPressed = touchPressed,
        )
}

/** Estado del D-pad (dirección 0–7 u 8 neutro, SPEC-004 §4). */
@JvmInline
value class DpadState(
    val value: Int,
) {
    companion object {
        val NEUTRAL = DpadState(8)
    }
}

/** Rangos de la Signature HID (SPEC-004 §2/§4); espejo de ProtocolConstants. */
private object ProtocolRanges {
    const val STICK_MAX = 127
    const val TRIGGER_MAX = 255
    const val TOUCH_MAX = 65_535
}
