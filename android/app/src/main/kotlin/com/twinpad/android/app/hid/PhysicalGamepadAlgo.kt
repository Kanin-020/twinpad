package com.twinpad.android.app.hid

import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import kotlin.math.abs
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
 * la Activity entrega teclas y el mapa de ejes del `MotionEvent`, y el
 * resultado sigue siendo un [InputReport] de la Signature de SPEC-004 §4
 * (mismos 12 botones, D-pad 0–8, sticks −127…127, triggers 0…255). El `seq`
 * lo asigna GamepadStateMachine.
 *
 * SPEC-011: el mapeo entrada física → entrada canónica es un [GamepadMapping]
 * por perfil; sin perfil aplica [GamepadMapping.DEFAULT], equivalente exacto
 * al mapeo de SPEC-004 §8.1. Los duplicados se permiten.
 *
 * Fusión por elemento (CR-028): el mando físico manda solo sobre los
 * elementos que está accionando (stick fuera de la zona muerta, D-pad
 * distinto de neutro, trigger > 0); en caso contrario el elemento conserva
 * el valor de la superficie táctil. `buttons` es el OR de ambas fuentes y
 * touch/motion viajan siempre desde la superficie táctil.
 */
class PhysicalGamepadAlgo {
    /** CR-032: distribución Nintendo de los botones de cara. Activada,
     * el mapeo física→Signature intercambia A↔B y X↔Y: el elemento en
     * posición de Nintendo (A derecha, B abajo, X arriba, Y izquierda)
     * es el que llega al juego. La Signature de SPEC-004 §4 no cambia. */
    var nintendoFaceDistribution: Boolean = false

    /** SPEC-011: mapeo vigente del mando (perfil activo o predeterminado). */
    var mapping: GamepadMapping = GamepadMapping.DEFAULT

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

    /** Teclas pulsadas vigentes (cualquier código del mapeo). */
    private val pressedKeys = mutableSetOf<Int>()

    /** Valores vigentes de los ejes del `MotionEvent`. */
    private var axes: Map<Int, Float> = emptyMap()

    /** Aplica el estado físico más reciente de un `MotionEvent` del mando
     * (CR-028) en la forma canónica de la Signature. */
    fun onMotion(motion: PhysicalMotion): Boolean =
        onAxes(
            mapOf(
                PhysicalAxes.AXIS_X to motion.leftX,
                PhysicalAxes.AXIS_Y to motion.leftY,
                PhysicalAxes.AXIS_Z to motion.rightX,
                PhysicalAxes.AXIS_RZ to motion.rightY,
                PhysicalAxes.AXIS_BRAKE to motion.triggerL,
                PhysicalAxes.AXIS_LTRIGGER to motion.triggerL,
                PhysicalAxes.AXIS_GAS to motion.triggerR,
                PhysicalAxes.AXIS_RTRIGGER to motion.triggerR,
                PhysicalAxes.AXIS_HAT_X to motion.hatX.toFloat(),
                PhysicalAxes.AXIS_HAT_Y to motion.hatY.toFloat(),
            ),
        )

    /** SPEC-011: aplica los valores de eje del mando tal como los reporta el
     * sistema; el mapeo decide qué eje alimenta cada elemento canónico. */
    fun onAxes(values: Map<Int, Float>): Boolean {
        axes = values
        return recompute()
    }

    /** Aplica una tecla del mando físico (keycode de `android.view.KeyEvent`,
     * ver [KeyCodes]). Devuelve `true` si el elemento cambió; `false` si el
     * código no pertenece al mapeo vigente. */
    fun onKey(
        keyCode: Int,
        pressed: Boolean,
    ): Boolean {
        if (!mapping.handlesKey(keyCode)) {
            if (pressed) return false
            pressedKeys.remove(keyCode)
            return recompute()
        }
        if (pressed) pressedKeys.add(keyCode) else pressedKeys.remove(keyCode)
        return recompute()
    }

    /** D-pad absoluto del hat de `MotionEvent` (AXIS_HAT_X/AXIS_HAT_Y −1/0/1). */
    fun onHat(
        hatX: Int,
        hatY: Int,
    ) {
        axes =
            axes +
            (PhysicalAxes.AXIS_HAT_X to hatX.toFloat()) +
            (PhysicalAxes.AXIS_HAT_Y to hatY.toFloat())
        recompute()
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
            dpad = pickDpad(touch.dpad, dpad),
            buttons = touch.buttons or buttons,
            triggerL2 = maxOf(touch.triggerL2, triggerL2),
            triggerR2 = maxOf(touch.triggerR2, triggerR2),
            motion = touch.motion,
            touchX = touch.touchX,
            touchY = touch.touchY,
            touchPressed = touch.touchPressed,
        )

    /** `true` si el keycode es un elemento del mapeo vigente: la Activity lo
     * consume cuando está en sesión; el resto pasa al SO. */
    fun handlesKey(keyCode: Int): Boolean = mapping.handlesKey(keyCode)

    /** Reposo del mando físico no pisa la superficie táctil. */
    private fun pickStick(
        touchValue: Int,
        physicalValue: Int,
    ): Int = if (physicalValue != 0) physicalValue else touchValue

    private fun pickDpad(
        touchValue: Int,
        physicalValue: Int,
    ): Int = if (physicalValue != ProtocolConstants.DPAD_NEUTRAL) physicalValue else touchValue

    /** Recalcula el estado canónico y dice si cambió algún elemento. */
    private fun recompute(): Boolean {
        val previous = listOf(buttons, dpad, stickLx, stickLy, stickRx, stickRy, triggerL2, triggerR2)
        buttons = mappedButtons()
        dpad = composedDpad()
        stickLx = mappedStick(MappedControl.STICK_LX)
        stickLy = mappedStick(MappedControl.STICK_LY)
        stickRx = mappedStick(MappedControl.STICK_RX)
        stickRy = mappedStick(MappedControl.STICK_RY)
        triggerL2 = mappedTrigger(MappedControl.TRIGGER_L2)
        triggerR2 = mappedTrigger(MappedControl.TRIGGER_R2)
        val current = listOf(buttons, dpad, stickLx, stickLy, stickRx, stickRy, triggerL2, triggerR2)
        return previous != current
    }

    private fun mappedButtons(): Int {
        var mask = 0
        for (button in Button.entries) {
            if (mapping.inputsOf(button.control).any(::activeButton)) {
                mask = mask or (1 shl button.effectiveBit(nintendoFaceDistribution))
            }
        }
        return mask
    }

    /** Botón físico activo: una tecla o una combinación con todas pulsadas
     * (SPEC-011 §7 paso 4, CR-123). */
    private fun activeButton(input: PhysicalInput): Boolean =
        when (input) {
            is PhysicalInput.Key -> input.keyCode in pressedKeys
            is PhysicalInput.Combo -> input.keyCodes.all { it in pressedKeys }
            is PhysicalInput.Axis -> false
        }

    private fun mappedTrigger(control: MappedControl): Int {
        val inputs = mapping.inputsOf(control)
        if (inputs.any(::activeButton)) {
            return ProtocolConstants.TRIGGER_MAX
        }
        val best =
            inputs
                .filterIsInstance<PhysicalInput.Axis>()
                .maxByOrNull { positiveAxis(it) }
        return best?.let { triggerFromAxis(positiveAxis(it)) } ?: 0
    }

    private fun positiveAxis(input: PhysicalInput.Axis): Float {
        return ((axes[input.axis] ?: 0f) * input.sign).coerceIn(0f, 1f)
    }

    private fun mappedStick(control: MappedControl): Int {
        val best =
            mapping.inputsOf(control)
                .filterIsInstance<PhysicalInput.Axis>()
                .maxByOrNull { abs(axes[it.axis] ?: 0f) }
                ?: return 0
        // SPEC-011 §8: el stick conserva la dirección física del eje (empujar
        // a la izquierda envía izquierda); el sentido solo normaliza gatillos
        // y direcciones de cruceta.
        return stickFromAxis(axes[best.axis] ?: 0f)
    }

    /** D-pad efectivo: direcciones activadas por tecla o por eje del hat. */
    private fun composedDpad(): Int {
        val directions =
            listOf(
                MappedControl.DPAD_UP to ProtocolConstants.DPAD_UP,
                MappedControl.DPAD_RIGHT to ProtocolConstants.DPAD_RIGHT,
                MappedControl.DPAD_DOWN to ProtocolConstants.DPAD_DOWN,
                MappedControl.DPAD_LEFT to ProtocolConstants.DPAD_LEFT,
            ).filter { (control, _) -> dpadActive(control) }
                .map { it.second }
                .toSet()
        if (directions.isEmpty()) return ProtocolConstants.DPAD_NEUTRAL
        return when (directions) {
            setOf(ProtocolConstants.DPAD_UP, ProtocolConstants.DPAD_RIGHT) -> NE
            setOf(ProtocolConstants.DPAD_RIGHT, ProtocolConstants.DPAD_DOWN) -> SE
            setOf(ProtocolConstants.DPAD_LEFT, ProtocolConstants.DPAD_DOWN) -> SW
            setOf(ProtocolConstants.DPAD_LEFT, ProtocolConstants.DPAD_UP) -> NW
            else -> singleOrNeutral(directions)
        }
    }

    private fun dpadActive(control: MappedControl): Boolean =
        mapping.inputsOf(control).any { input ->
            if (input is PhysicalInput.Axis) {
                (axes[input.axis] ?: 0f) * input.sign >= DPAD_AXIS_THRESHOLD
            } else {
                activeButton(input)
            }
        }

    private fun singleOrNeutral(directions: Set<Int>): Int {
        if (directions.size > 1) return ProtocolConstants.DPAD_NEUTRAL // direcciones opuestas
        return directions.first()
    }

    private fun stickFromAxis(axis: Float): Int {
        val clamped = axis.coerceIn(-1f, 1f)
        if (clamped < STICK_DEADZONE && clamped > -STICK_DEADZONE) return 0
        return (clamped * ProtocolConstants.STICK_MAX).roundToInt()
    }

    private fun triggerFromAxis(axis: Float): Int = (axis.coerceIn(0f, 1f) * ProtocolConstants.TRIGGER_MAX).roundToInt()

    /** Botones de la Signature en orden de bits de SPEC-004 §4, con su
     * keycode de `android.view.KeyEvent` (−1 = sin keycode estándar).
     * Los bits y keycodes son constantes canónicas del protocolo/plataforma. */
    @Suppress("MagicNumber")
    enum class Button(
        val bit: Int,
        val keyCode: Int,
        val control: MappedControl,
    ) {
        A(0, KeyCodes.BUTTON_A, MappedControl.A),
        B(1, KeyCodes.BUTTON_B, MappedControl.B),
        X(2, KeyCodes.BUTTON_X, MappedControl.X),
        Y(3, KeyCodes.BUTTON_Y, MappedControl.Y),
        L1(4, KeyCodes.BUTTON_L1, MappedControl.L1),
        R1(5, KeyCodes.BUTTON_R1, MappedControl.R1),
        L3(6, KeyCodes.BUTTON_L3, MappedControl.L3),
        R3(7, KeyCodes.BUTTON_R3, MappedControl.R3),
        START(8, KeyCodes.BUTTON_START, MappedControl.START),
        SELECT(9, KeyCodes.BUTTON_SELECT, MappedControl.SELECT),
        EXTRA_1(10, KeyCodes.BUTTON_MODE, MappedControl.EXTRA_1),
        EXTRA_2(11, -1, MappedControl.EXTRA_2),
        ;

        /** CR-032: con distribución Nintendo la tecla reportada como A
         * (posición abajo en mandos Xbox/Play) envía B, y viceversa;
         * ídem X↔Y. L1/R1/L3/R3/START/SELECT/EXTRA no participan del intercambio. */
        fun effectiveBit(nintendoFaceDistribution: Boolean): Int =
            when {
                !nintendoFaceDistribution -> bit
                this == A -> B.bit
                this == B -> A.bit
                this == X -> Y.bit
                this == Y -> X.bit
                else -> bit
            }
    }

    /** Keycodes de `android.view.KeyEvent` usados por el mapper (CR-028).
     * Son constantes de la plataforma Android, definidas por AOSP; los
     * valores nombrados que siguen no son números mágicos de negocio. */
    object KeyCodes {
        const val DPAD_UP = PhysicalKeys.DPAD_UP
        const val DPAD_DOWN = PhysicalKeys.DPAD_DOWN
        const val DPAD_LEFT = PhysicalKeys.DPAD_LEFT
        const val DPAD_RIGHT = PhysicalKeys.DPAD_RIGHT
        const val BUTTON_A = PhysicalKeys.BUTTON_A
        const val BUTTON_B = PhysicalKeys.BUTTON_B
        const val BUTTON_X = PhysicalKeys.BUTTON_X
        const val BUTTON_Y = PhysicalKeys.BUTTON_Y
        const val BUTTON_L1 = PhysicalKeys.BUTTON_L1
        const val BUTTON_R1 = PhysicalKeys.BUTTON_R1
        const val BUTTON_L2 = PhysicalKeys.BUTTON_L2
        const val BUTTON_R2 = PhysicalKeys.BUTTON_R2
        const val BUTTON_START = PhysicalKeys.BUTTON_START
        const val BUTTON_SELECT = PhysicalKeys.BUTTON_SELECT

        /** Home/Guide/PS del mando físico (SPEC-011: fila `extra_1`). */
        const val BUTTON_MODE = PhysicalKeys.BUTTON_MODE
        const val BUTTON_L3 = PhysicalKeys.BUTTON_L3
        const val BUTTON_R3 = PhysicalKeys.BUTTON_R3
    }

    private companion object {
        /** Zona muerta de sticks físicos (normalizado); sin reescalado. */
        const val STICK_DEADZONE = 0.1f

        /** Umbral del eje del hat para activar una dirección. */
        const val DPAD_AXIS_THRESHOLD = 0.5f

        /** Diagonales del D-pad (SPEC-004 §4: sentido horario desde el norte). */
        const val NE = 1
        const val SE = 3
        const val SW = 5
        const val NW = 7
    }
}
