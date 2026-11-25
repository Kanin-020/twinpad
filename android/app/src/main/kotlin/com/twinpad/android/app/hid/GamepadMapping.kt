package com.twinpad.android.app.hid

import kotlin.math.abs

/** Entrada física de un mando: una tecla o un eje con su sentido. */
sealed interface PhysicalInput {
    data class Key(val keyCode: Int) : PhysicalInput

    /** Eje de `MotionEvent`; [sign] normaliza el sentido observado al capturar. */
    data class Axis(val axis: Int, val sign: Int = 1) : PhysicalInput
}

/** Entradas canónicas ajustables de la Signature (SPEC-011 §4). El giroscopio
 * queda fuera: no se reasigna. */
enum class MappedControl(
    val label: String,
) {
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    L1("LB"),
    R1("RB"),
    L3("Stick izquierdo"),
    R3("Stick derecho"),
    START("Start"),
    SELECT("Select"),
    EXTRA_1("Botón extra 1"),
    EXTRA_2("Botón extra 2"),
    STICK_LX("Stick izquierdo ← →"),
    STICK_LY("Stick izquierdo ↑ ↓"),
    STICK_RX("Stick derecho ← →"),
    STICK_RY("Stick derecho ↑ ↓"),
    TRIGGER_L2("Gatillo LT"),
    TRIGGER_R2("Gatillo RT"),
    DPAD_UP("Cruceta ↑"),
    DPAD_DOWN("Cruceta ↓"),
    DPAD_LEFT("Cruceta ←"),
    DPAD_RIGHT("Cruceta →"),
    ;

    /** Los ejes de stick solo aceptan movimiento; el resto acepta botón o eje. */
    val stickAxis: Boolean
        get() = this in setOf(STICK_LX, STICK_LY, STICK_RX, STICK_RY)

    /** Los botones solo aceptan teclas; gatillos y cruceta aceptan ambas. */
    val button: Boolean
        get() =
            this in
                setOf(
                    A,
                    B,
                    X,
                    Y,
                    L1,
                    R1,
                    L3,
                    R3,
                    START,
                    SELECT,
                    EXTRA_1,
                    EXTRA_2,
                )
}

/** Mapeo entrada canónica → entradas físicas. Los duplicados están permitidos
 * (SPEC-011 §7): una misma entrada física puede activar varias canónicas. */
data class GamepadMapping(
    private val inputs: Map<MappedControl, List<PhysicalInput>>,
) {
    fun inputsOf(control: MappedControl): List<PhysicalInput> = inputs[control].orEmpty()

    /** Captura de una canónica: la entrada nueva reemplaza a las anteriores
     * de esa fila; otra fila puede conservar la misma entrada física
     * (duplicados permitidos entre canónicas, SPEC-011 AC-2/AC-5). */
    fun with(
        control: MappedControl,
        input: PhysicalInput,
    ): GamepadMapping {
        if (inputsOf(control) == listOf(input)) return this
        return copy(inputs = inputs + (control to listOf(input)))
    }

    /** `true` si la tecla pertenece al mapeo vigente (se consume en sesión). */
    fun handlesKey(keyCode: Int): Boolean {
        return inputs.values.any { list -> list.any { it is PhysicalInput.Key && it.keyCode == keyCode } }
    }

    companion object {
        /** Equivalencias automáticas de SPEC-004 §8.1 (SPEC-011 AC-1). */
        val DEFAULT: GamepadMapping =
            GamepadMapping(
                buildMap {
                    put(MappedControl.A, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_A)))
                    put(MappedControl.B, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_B)))
                    put(MappedControl.X, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_X)))
                    put(MappedControl.Y, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_Y)))
                    put(MappedControl.L1, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_L1)))
                    put(MappedControl.R1, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_R1)))
                    put(MappedControl.L3, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_L3)))
                    put(MappedControl.R3, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_R3)))
                    put(MappedControl.START, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_START)))
                    put(MappedControl.SELECT, listOf(PhysicalInput.Key(PhysicalKeys.BUTTON_SELECT)))
                    put(
                        MappedControl.STICK_LX,
                        listOf(PhysicalInput.Axis(PhysicalAxes.AXIS_X)),
                    )
                    put(
                        MappedControl.STICK_LY,
                        listOf(PhysicalInput.Axis(PhysicalAxes.AXIS_Y)),
                    )
                    put(
                        MappedControl.STICK_RX,
                        listOf(PhysicalInput.Axis(PhysicalAxes.AXIS_Z)),
                    )
                    put(
                        MappedControl.STICK_RY,
                        listOf(PhysicalInput.Axis(PhysicalAxes.AXIS_RZ)),
                    )
                    put(
                        MappedControl.TRIGGER_L2,
                        listOf(
                            PhysicalInput.Axis(PhysicalAxes.AXIS_BRAKE),
                            PhysicalInput.Axis(PhysicalAxes.AXIS_LTRIGGER),
                            PhysicalInput.Key(PhysicalKeys.BUTTON_L2),
                        ),
                    )
                    put(
                        MappedControl.TRIGGER_R2,
                        listOf(
                            PhysicalInput.Axis(PhysicalAxes.AXIS_GAS),
                            PhysicalInput.Axis(PhysicalAxes.AXIS_RTRIGGER),
                            PhysicalInput.Key(PhysicalKeys.BUTTON_R2),
                        ),
                    )
                    put(
                        MappedControl.DPAD_UP,
                        listOf(
                            PhysicalInput.Key(PhysicalKeys.DPAD_UP),
                            PhysicalInput.Axis(PhysicalAxes.AXIS_HAT_Y, -1),
                        ),
                    )
                    put(
                        MappedControl.DPAD_DOWN,
                        listOf(
                            PhysicalInput.Key(PhysicalKeys.DPAD_DOWN),
                            PhysicalInput.Axis(PhysicalAxes.AXIS_HAT_Y, 1),
                        ),
                    )
                    put(
                        MappedControl.DPAD_LEFT,
                        listOf(
                            PhysicalInput.Key(PhysicalKeys.DPAD_LEFT),
                            PhysicalInput.Axis(PhysicalAxes.AXIS_HAT_X, -1),
                        ),
                    )
                    put(
                        MappedControl.DPAD_RIGHT,
                        listOf(
                            PhysicalInput.Key(PhysicalKeys.DPAD_RIGHT),
                            PhysicalInput.Axis(PhysicalAxes.AXIS_HAT_X, 1),
                        ),
                    )
                },
            )
    }
}

/** Keycodes de `android.view.KeyEvent` (constantes de la plataforma AOSP). */
@Suppress("MagicNumber")
object PhysicalKeys {
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

/** Ejes de `MotionEvent` (constantes de la plataforma AOSP). */
@Suppress("MagicNumber")
object PhysicalAxes {
    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_Z = 11
    const val AXIS_RX = 12
    const val AXIS_RY = 13
    const val AXIS_RZ = 14
    const val AXIS_HAT_X = 15
    const val AXIS_HAT_Y = 16
    const val AXIS_LTRIGGER = 17
    const val AXIS_RTRIGGER = 18
    const val AXIS_THROTTLE = 19
    const val AXIS_GAS = 22
    const val AXIS_BRAKE = 23

    /** Ejes que la app entrega al mapper al leer un `MotionEvent`. */
    val tracked: List<Int> =
        listOf(
            AXIS_X,
            AXIS_Y,
            AXIS_Z,
            AXIS_RX,
            AXIS_RY,
            AXIS_RZ,
            AXIS_HAT_X,
            AXIS_HAT_Y,
            AXIS_LTRIGGER,
            AXIS_RTRIGGER,
            AXIS_THROTTLE,
            AXIS_GAS,
            AXIS_BRAKE,
        )
}

/** Umbral de captura de un eje (valor absoluto). */
const val CAPTURE_AXIS_THRESHOLD = 0.5f

/** Captura de un botón: la siguiente tecla es la entrada. */
fun captureKey(keyCode: Int): PhysicalInput = PhysicalInput.Key(keyCode)

/** Captura de un eje: el eje movido con mayor valor absoluto sobre el umbral,
 * con el sentido observado (SPEC-011 §7 paso 4). */
fun captureAxis(axes: Map<Int, Float>): PhysicalInput.Axis? {
    val candidate =
        axes.entries
            .filter { it.value.isFinite() && abs(it.value) >= CAPTURE_AXIS_THRESHOLD }
            .maxByOrNull { abs(it.value) }
            ?: return null
    return PhysicalInput.Axis(candidate.key, if (candidate.value >= 0f) 1 else -1)
}

/** Identidad de modelo de un mando (SPEC-011 §3, CR-116): los ids de
 * fabricante/producto de `InputDevice` y su nombre; el descriptor es el
 * respaldo cuando el sistema no expone ids ni nombre. */
fun gamepadModelKey(
    vendorId: Int,
    productId: Int,
    name: String?,
    descriptor: String?,
): String {
    val model = name?.trim().orEmpty()
    if (vendorId != 0 || productId != 0 || model.isNotEmpty()) {
        return "v$vendorId|p$productId|$model"
    }
    return descriptor?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
}
