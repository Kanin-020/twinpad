package com.twinpad.android.app.hid

import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import kotlin.math.roundToInt

/** Estado de un evento de movimiento del mando físico (CR-028): ejes de
 * sticks/triggers normalizados y hat del D-pad en −1/0/1, tal como los
 * entrega `MotionEvent` en PairingActivity. */
data class PhysicalMotion(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val triggerL: Float,
    val triggerR: Float,
    val hatX: Int,
    val hatY: Int,
)

/**
 * Mando físico conectado al teléfono (CR-028, SPEC-004): mapper puro y
 * fusión con la superficie táctil. Sin imports de Android (ENG §5 P11);
 * la Activity traduce `KeyEvent`/`MotionEvent` de origen gamepad a los
 * códigos/ejes de esta clase y el resultado sigue siendo un [InputReport]
 * de la Signature de SPEC-004 §4 (mismos 12 botones, D-pad 0–8, sticks
 * −127…127, triggers 0…255). El `seq` lo asigna GamepadStateMachine.
 *
 * Fusión por elemento (CR-028): el mando físico manda solo sobre los
 * elementos que está accionando (stick fuera de la zona muerta, D-pad
 * distinto de neutro, trigger > 0); en caso contrario el elemento conserva
 * el valor de la superficie táctil. `buttons` es el OR de ambas fuentes y
 * touch/motion viajan siempre desde la superficie táctil.
 */
class PhysicalGamepadAlgo {
    var stickLx = 0
        private set
    var stickLy = 0
        private set
    var stickRx = 0
        private set
    var stickRy = 0
        private set
    var triggerL2 = 0
        private set
    var triggerR2 = 0
        private set
    var dpad = ProtocolConstants.DPAD_NEUTRAL
        private set
    var buttons = 0
        private set

    /** Direcciones D-pad activadas por teclas (composición multi-dirección). */
    private val dpadKeys = mutableSetOf<Int>()

    /** Dirección absoluta del hat del mando (8 = sin hat/neutro). */
    private var hatDirection = ProtocolConstants.DPAD_NEUTRAL

    /** Aplica el estado físico más reciente de un `MotionEvent` del mando
     * (CR-028). Valores bajo [STICK_DEADZONE] caen a 0 (zona muerta). */
    fun onMotion(motion: PhysicalMotion) {
        stickLx = stickFromAxis(motion.leftX)
        stickLy = stickFromAxis(motion.leftY)
        stickRx = stickFromAxis(motion.rightX)
        stickRy = stickFromAxis(motion.rightY)
        triggerL2 = triggerFromAxis(motion.triggerL)
        triggerR2 = triggerFromAxis(motion.triggerR)
        onHat(motion.hatX, motion.hatY)
    }

    /** Aplica una tecla del mando físico (keycode de `android.view.KeyEvent`,
     * ver [KeyCodes]). Devuelve `true` si el elemento cambió; `false` si el
     * código no pertenece a la Signature. */
    fun onKey(
        keyCode: Int,
        pressed: Boolean,
    ): Boolean =
        when (keyCode) {
            KeyCodes.BUTTON_L2, KeyCodes.BUTTON_R2 -> applyTriggerKey(keyCode, pressed)
            KeyCodes.DPAD_LEFT, KeyCodes.DPAD_DOWN, KeyCodes.DPAD_RIGHT, KeyCodes.DPAD_UP ->
                applyDpadKey(keyCode, pressed)
            else -> applyButtonKey(keyCode, pressed)
        }

    /** D-pad absoluto del hat de `MotionEvent` (AXIS_HAT_X/HAT_Y −1/0/1): */
    fun onHat(
        hatX: Int,
        hatY: Int,
    ) {
        hatDirection = hatToDirection(hatX, hatY)
    }

    /**
     * Fusiona el estado físico con el snapshot de la superficie táctil
     * ([touch], ver GamepadSurfaceState) y devuelve el reporte combinado
     * con `seq = 0`. Coordenadas táctiles e inercia viajan desde [touch];
     * si el mando físico no acciona un elemento, ese elemento conserva el
     * valor táctil (regla de fusión de CR-028).
     */
    fun fusedWith(touch: InputReport): InputReport =
        InputReport(
            seq = 0,
            stickLx = pickStick(touch.stickLx, stickLx),
            stickLy = pickStick(touch.stickLy, stickLy),
            stickRx = pickStick(touch.stickRx, stickRx),
            stickRy = pickStick(touch.stickRy, stickRy),
            dpad = pickDpad(touch.dpad, composedDpad()),
            buttons = touch.buttons or buttons,
            triggerL2 = maxOf(touch.triggerL2, triggerL2),
            triggerR2 = maxOf(touch.triggerR2, triggerR2),
            motion = touch.motion,
            touchX = touch.touchX,
            touchY = touch.touchY,
            touchPressed = touch.touchPressed,
        )

    /** `true` si el keycode es un elemento de la Signature (CR-028): la
     * Activity lo consume cuando está en sesión; el resto pasa al SO. */
    fun handlesKey(keyCode: Int): Boolean =
        keyToButton(keyCode) != null ||
            keyCode == KeyCodes.BUTTON_L2 ||
            keyCode == KeyCodes.BUTTON_R2 ||
            DPAD_KEYCODES.any { keyCode == it }

    /** Reposo del mando físico no pisa la superficie táctil. */
    private fun pickStick(
        touchValue: Int,
        physicalValue: Int,
    ): Int = if (physicalValue != 0) physicalValue else touchValue

    private fun pickDpad(
        touchValue: Int,
        physicalValue: Int,
    ): Int = if (physicalValue != ProtocolConstants.DPAD_NEUTRAL) physicalValue else touchValue

    /** D-pad efectivo del mando físico: el hat manda cuando está activo;
     * si no, composición de teclas D-pad simultáneas (diagonales por OR). */
    private fun composedDpad(): Int =
        if (hatDirection != ProtocolConstants.DPAD_NEUTRAL) {
            hatDirection
        } else {
            composeDpadKeys()
        }

    private fun composeDpadKeys(): Int {
        val directions = dpadKeys.toSet()
        if (directions.isEmpty()) return ProtocolConstants.DPAD_NEUTRAL
        return when (directions) {
            setOf(ProtocolConstants.DPAD_UP, ProtocolConstants.DPAD_RIGHT) -> NE
            setOf(ProtocolConstants.DPAD_RIGHT, ProtocolConstants.DPAD_DOWN) -> SE
            setOf(ProtocolConstants.DPAD_LEFT, ProtocolConstants.DPAD_DOWN) -> SW
            setOf(ProtocolConstants.DPAD_LEFT, ProtocolConstants.DPAD_UP) -> NW
            else -> singleOrNeutral(directions)
        }
    }

    private fun singleOrNeutral(directions: Set<Int>): Int {
        if (directions.size > 1) return ProtocolConstants.DPAD_NEUTRAL // direcciones opuestas
        return directions.first()
    }

    private fun applyTriggerKey(
        keyCode: Int,
        pressed: Boolean,
    ): Boolean {
        val isLeft = keyCode == KeyCodes.BUTTON_L2
        val updated = if (pressed) ProtocolConstants.TRIGGER_MAX else 0
        val changed = if (isLeft) triggerL2 != updated else triggerR2 != updated
        if (isLeft) triggerL2 = updated else triggerR2 = updated
        return changed
    }

    private fun applyDpadKey(
        keyCode: Int,
        pressed: Boolean,
    ): Boolean {
        val direction = keyToDpadDirection(keyCode)
        val changedInSet = if (pressed) dpadKeys.add(direction) else dpadKeys.remove(direction)
        val composed = composedDpad()
        val changed = changedInSet || composed != dpad
        dpad = composed
        return changed
    }

    private fun keyToDpadDirection(keyCode: Int): Int =
        when (keyCode) {
            KeyCodes.DPAD_UP -> ProtocolConstants.DPAD_UP
            KeyCodes.DPAD_RIGHT -> ProtocolConstants.DPAD_RIGHT
            KeyCodes.DPAD_DOWN -> ProtocolConstants.DPAD_DOWN
            else -> ProtocolConstants.DPAD_LEFT
        }

    private fun applyButtonKey(
        keyCode: Int,
        pressed: Boolean,
    ): Boolean {
        val button = keyToButton(keyCode) ?: return false
        val mask = 1 shl button.bit
        val updated = if (pressed) buttons or mask else buttons and mask.inv()
        if (updated == buttons) return false
        buttons = updated
        return true
    }

    private fun stickFromAxis(axis: Float): Int {
        val clamped = axis.coerceIn(-1f, 1f)
        if (clamped < STICK_DEADZONE && clamped > -STICK_DEADZONE) return 0
        return (clamped * ProtocolConstants.STICK_MAX).roundToInt()
    }

    private fun triggerFromAxis(axis: Float): Int =
        (axis.coerceIn(0f, 1f) * ProtocolConstants.TRIGGER_MAX).roundToInt()

    private fun keyToButton(keyCode: Int): Button? =
        Button.entries.find { it.keyCode == keyCode }

    /** Botones de la Signature en orden de bits de SPEC-004 §4, con su
     * keycode de `android.view.KeyEvent` (−1 = sin keycode estándar).
     * Los bits y keycodes son constantes canónicas del protocolo/plataforma. */
    @Suppress("MagicNumber")
    enum class Button(
        val bit: Int,
        val keyCode: Int,
    ) {
        A(0, KeyCodes.BUTTON_A),
        B(1, KeyCodes.BUTTON_B),
        X(2, KeyCodes.BUTTON_X),
        Y(3, KeyCodes.BUTTON_Y),
        L1(4, KeyCodes.BUTTON_L1),
        R1(5, KeyCodes.BUTTON_R1),
        L3(6, KeyCodes.BUTTON_L3),
        R3(7, KeyCodes.BUTTON_R3),
        START(8, KeyCodes.BUTTON_START),
        SELECT(9, KeyCodes.BUTTON_SELECT),
        EXTRA_1(10, -1),
        EXTRA_2(11, -1),
    }

    /** Keycodes de `android.view.KeyEvent` usados por el mapper (CR-028).
     * Son constantes de la plataforma Android, definidas por AOSP; los
     * valores nombrados que siguen no son números mágicos de negocio. */
    @Suppress("MagicNumber")
    object KeyCodes {
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val BUTTON_A = 96
        const val BUTTON_B = 97
        const val BUTTON_X = 99
        const val BUTTON_Y = 100
        const val BUTTON_L1 = 102
        const val BUTTON_R1 = 103
        const val BUTTON_L2 = 104
        const val BUTTON_R2 = 105
        const val BUTTON_START = 108
        const val BUTTON_SELECT = 109
        const val BUTTON_L3 = 110
        const val BUTTON_R3 = 111
    }

    private companion object {
        /** Zona muerta de sticks físicos (normalizado); sin reescalado. */
        const val STICK_DEADZONE = 0.1f

        /** Diagonales del D-pad (SPEC-004 §4: sentido horario desde el norte). */
        const val NE = 1
        const val SE = 3
        const val SW = 5
        const val NW = 7

        /** Keycodes D-pad que el mapper trata como dirección (CR-028). */
        val DPAD_KEYCODES: List<Int> =
            listOf(KeyCodes.DPAD_UP, KeyCodes.DPAD_RIGHT, KeyCodes.DPAD_DOWN, KeyCodes.DPAD_LEFT)

        /** Hat (hatX, hatY) → dirección D-pad; mapping canónico §4. */
        val HAT_TO_DIRECTION: Map<Pair<Int, Int>, Int> =
            mapOf(
                (0 to -1) to ProtocolConstants.DPAD_UP,
                (1 to -1) to NE,
                (1 to 0) to ProtocolConstants.DPAD_RIGHT,
                (1 to 1) to SE,
                (0 to 1) to ProtocolConstants.DPAD_DOWN,
                (-1 to 1) to SW,
                (-1 to 0) to ProtocolConstants.DPAD_LEFT,
                (-1 to -1) to NW,
            )

        private fun hatToDirection(
            hatX: Int,
            hatY: Int,
        ): Int = HAT_TO_DIRECTION[hatX to hatY] ?: ProtocolConstants.DPAD_NEUTRAL
    }
}
