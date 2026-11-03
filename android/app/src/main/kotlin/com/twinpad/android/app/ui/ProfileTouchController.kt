package com.twinpad.android.app.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.TouchControl
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Consulta de hit test agrupada (ENG P4, detekt LongParameterList):
 * punto de toque, superficie y margen de percepción (px). */
internal data class HitQuery(
    val px: Float,
    val py: Float,
    val surfaceWidth: Float,
    val surfaceHeight: Float,
    val marginPx: Float = 0f,
)

/**
 * ¿El punto `query.px, query.py` cae dentro de la forma que el renderer
 * dibuja para el control (más el margen de percepción, CR-072)? Todos los
 * controles se pintan como el círculo inscrito en sus bounds (lado menor,
 * CR-068): esta es la hitbox del toque, idéntica a la forma visible (el
 * D-pad incluido, cuyo plato es el mismo círculo).
 */
internal fun insideDrawnShape(
    query: HitQuery,
    control: TouchControl,
): Boolean {
    val cx = (control.x + control.w / 2f) * query.surfaceWidth
    val cy = (control.y + control.h / 2f) * query.surfaceHeight
    val radius = minOf(control.w * query.surfaceWidth, control.h * query.surfaceHeight) / 2f + query.marginPx
    val dx = query.px - cx
    val dy = query.py - cy
    return dx * dx + dy * dy <= radius * radius
}

/**
 * Índice del control que captura el toque (CR-072). Entre todos los controles
 * cuya hitbox contiene el punto gana la de MENOR radio: los iconos pequeños
 * junto a uno grande (clicks L3/R3 sobre el plato del stick) están dibujados
 * encima y son el objetivo real; con first-match, cualquier tap entre ambos
 * iconos se lo llevaba el stick y lo iluminaba/desviaba sin tocarlo. A
 * igualdad gana el último del orden de dibujo (el top visible).
 */
internal fun hitIndex(
    profile: ControlProfile,
    query: HitQuery,
): Int {
    var bestIndex = -1
    var bestRadius = Float.MAX_VALUE
    profile.controls.forEachIndexed { index, control ->
        val radius = minOf(control.w * query.surfaceWidth, control.h * query.surfaceHeight) / 2f + query.marginPx
        if (radius <= bestRadius && insideDrawnShape(query, control)) {
            bestIndex = index
            bestRadius = radius
        }
    }
    return bestIndex
}

private const val DPAD_NEUTRAL = 8
private const val DPAD_NORTH = 0
private const val DPAD_NORTH_EAST = 1
private const val DPAD_EAST = 2
private const val DPAD_SOUTH_EAST = 3
private const val DPAD_SOUTH = 4
private const val DPAD_SOUTH_WEST = 5
private const val DPAD_WEST = 6
private const val DPAD_NORTH_WEST = 7
private const val DEGREES_PER_DPAD_SECTOR = 45f
private const val DPAD_HALF_SECTOR_DEGREES = 22.5f
private const val DEGREES_PER_FULL_TURN = 360f
private const val DPAD_DIRECTIONS = 8
private const val MAX_ACTIVE_POINTERS = 10
private const val MAX_TOUCH_STICKS = 2
private const val DIGITAL_DPAD_RADIUS_SCALE = 0.65f

/** Margen de percepción del toque (CR-072, × density): hitbox = forma
 * dibujada + margen, para que los clicks L3/R3 junto a los sticks capten
 * los taps de sus bordes y no el plato del stick contiguo. */
private const val HITBOX_MARGIN_DP = 8f
private const val DIAGONAL_NORTH_EAST_SECTOR = 1
private const val DIAGONAL_SOUTH_EAST_SECTOR = 3
private const val DIAGONAL_SOUTH_WEST_SECTOR = 5

/** Routes touch gestures to the current profile and gamepad snapshot. */
internal class ProfileTouchController(
    private val surface: GamepadSurfaceState,
    private val onInput: (InputReport) -> Unit,
    private val contentRect: () -> VideoContentRect = { VideoContentRect(0f, 0f, 1f, 1f) },
) {
    /** Si es false, no hay botones táctiles: los toques van a la pantalla DRC. */
    var controlsVisible: Boolean = true
    private val activePointers = linkedMapOf<Int, Int>()
    private val pointerPositions = linkedMapOf<Int, Pair<Float, Float>>()
    private var drcTouchActive = false
    private var drcPointerId: Int? = null

    /** Índice del control con entrada activa (para el render `pressed`). */
    private var draggingIndex = -1

    fun renderState() =
        ProfileRenderState(
            draggingIndex,
            activePointers,
            pointerPositions,
        )

    fun onTouch(
        event: MotionEvent,
        view: View,
        profile: ControlProfile,
    ): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onPointerDown(event, view, profile)
            MotionEvent.ACTION_MOVE -> onPointersMove(event, view, profile)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onPointerUp(event, profile)
            MotionEvent.ACTION_CANCEL -> cancelPointers(profile)
            else -> false
        }

    private fun onPointerDown(
        event: MotionEvent,
        view: View,
        profile: ControlProfile,
    ): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        val normalizedX = event.getX(index) / view.width
        val normalizedY = event.getY(index) / view.height
        val target = hitTest(event.getX(index), event.getY(index), view, profile)
        if (target == null) return beginDrcTouch(pointerId, normalizedX, normalizedY, view)
        if (!canStartControlPointer(target.index, profile)) return false
        activePointers[pointerId] = target.index
        pointerPositions[pointerId] = target.nx to target.ny
        draggingIndex = target.index
        applyControl(target.index, target.nx, target.ny, true, profile)
        view.invalidate()
        return true
    }

    private fun beginDrcTouch(
        pointerId: Int,
        nx: Float,
        ny: Float,
        view: View,
    ): Boolean {
        if (drcPointerId != null) return false
        if (!updateDrcTouch(nx, ny, true, view)) return false
        drcPointerId = pointerId
        drcTouchActive = true
        emit()
        return true
    }

    private fun canStartControlPointer(
        index: Int,
        profile: ControlProfile,
    ): Boolean {
        if (activePointers.size >= MAX_ACTIVE_POINTERS || activePointers.values.contains(index)) return false
        val isStick = profile.controls[index].kind == ControlProfileCodec.KIND_STICK
        val activeStickCount =
            activePointers.values.count { active ->
                profile.controls[active].kind == ControlProfileCodec.KIND_STICK
            }
        return !isStick || activeStickCount < MAX_TOUCH_STICKS
    }

    private fun onPointersMove(
        event: MotionEvent,
        view: View,
        profile: ControlProfile,
    ): Boolean {
        if (activePointers.isEmpty() && drcPointerId == null) return false
        for (index in 0 until event.pointerCount) {
            val motion =
                TouchPointerMotion(
                    event,
                    index,
                    event.getPointerId(index),
                    event.getX(index) / view.width,
                    event.getY(index) / view.height,
                    view,
                )
            if (motion.pointerId == drcPointerId) {
                updateDrcTouch(motion.normalizedX, motion.normalizedY, true, view)
                emit()
                continue
            }
            updateControlPointer(motion, profile)
        }
        return true
    }

    private fun updateControlPointer(
        motion: TouchPointerMotion,
        profile: ControlProfile,
    ) {
        val controlIndex = activePointers[motion.pointerId] ?: return
        val control = profile.controls[controlIndex]
        val (adjustedX, adjustedY) =
            adjustDpadPosition(motion.normalizedX, motion.normalizedY, control)
        pointerPositions[motion.pointerId] = adjustedX to adjustedY
        applyControl(controlIndex, adjustedX, adjustedY, true, profile)
        motion.view.invalidate()
    }

    private fun adjustDpadPosition(
        nx: Float,
        ny: Float,
        control: TouchControl,
    ): Pair<Float, Float> {
        if (control.kind != ControlProfileCodec.KIND_DPAD) return nx to ny
        val centerX = control.x + control.w / 2f
        val centerY = control.y + control.h / 2f
        val maxOffsetX = control.w * DIGITAL_DPAD_RADIUS_SCALE
        val maxOffsetY = control.h * DIGITAL_DPAD_RADIUS_SCALE
        val offsetX = (nx - centerX).coerceIn(-maxOffsetX, maxOffsetX)
        val offsetY = (ny - centerY).coerceIn(-maxOffsetY, maxOffsetY)
        return (centerX + offsetX) to (centerY + offsetY)
    }

    private fun onPointerUp(
        event: MotionEvent,
        profile: ControlProfile,
    ): Boolean {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerId == drcPointerId) {
            drcPointerId = null
            drcTouchActive = false
            val snapshot = surface.snapshot()
            surface.setTouch(snapshot.touchX, snapshot.touchY, pressed = false)
            emit()
            return true
        }
        val controlIndex = activePointers.remove(pointerId) ?: return activePointers.isNotEmpty()
        pointerPositions.remove(pointerId)
        releaseControl(controlIndex, profile)
        draggingIndex = activePointers.values.lastOrNull() ?: -1
        return true
    }

    private fun cancelPointers(profile: ControlProfile): Boolean {
        val pressedControls = activePointers.values.toList()
        activePointers.clear()
        draggingIndex = -1
        pointerPositions.clear()
        if (drcTouchActive) {
            drcPointerId = null
            drcTouchActive = false
            val snapshot = surface.snapshot()
            surface.setTouch(snapshot.touchX, snapshot.touchY, pressed = false)
            emit()
        }
        pressedControls.forEach { releaseControl(it, profile) }
        return true
    }

    private fun releaseControl(
        index: Int,
        profile: ControlProfile,
    ) {
        val control = profile.controls.getOrNull(index) ?: return
        when {
            control.kind == ControlProfileCodec.KIND_STICK -> surface.setStick(0f, 0f, control.hid == "stick_l")
            control.hid == "dpad" -> surface.setDpad(DPAD_NEUTRAL)
            else -> applyButtonOrTrigger(control, false)
        }
        emit()
    }

    /**
     * Mapea el toque al rectángulo donde se dibuja el video (CR-025). Devuelve false
     * si el toque cae fuera de esa imagen (barras de `contain`) y no altera el estado.
     */
    private fun updateDrcTouch(
        nx: Float,
        ny: Float,
        pressed: Boolean,
        view: View,
    ): Boolean {
        val videoSurface = videoSurfaceContainer(view) ?: return false
        val viewWidth = videoSurface.width.toFloat().coerceAtLeast(1f)
        val viewHeight = videoSurface.height.toFloat().coerceAtLeast(1f)
        val viewLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        videoSurface.getLocationOnScreen(viewLocation)
        view.getLocationOnScreen(surfaceLocation)
        val pointerX = nx * view.width - (viewLocation[0] - surfaceLocation[0])
        val pointerY = ny * view.height - (viewLocation[1] - surfaceLocation[1])
        val fraction = contentRect()
        val imageX = (pointerX / viewWidth - fraction.left) / fraction.width
        val imageY = (pointerY / viewHeight - fraction.top) / fraction.height
        if (imageX !in 0f..1f || imageY !in 0f..1f) return false
        surface.setTouch(
            (imageX * ProtocolConstants.TOUCH_MAX).roundToInt(),
            (imageY * ProtocolConstants.TOUCH_MAX).roundToInt(),
            pressed,
        )
        return true
    }

    private fun applyControl(
        index: Int,
        nx: Float,
        ny: Float,
        pressed: Boolean,
        profile: ControlProfile,
    ) {
        val control = profile.controls.getOrNull(index) ?: return
        when {
            control.kind == ControlProfileCodec.KIND_STICK -> applyStick(surface, control, nx, ny, pressed)
            control.hid == "dpad" -> surface.setDpad(if (pressed) quadrantDirection(nx, ny, control) else DPAD_NEUTRAL)
            else -> applyButtonOrTrigger(control, pressed)
        }
        emit()
    }

    private fun applyButtonOrTrigger(
        control: TouchControl,
        pressed: Boolean,
    ) {
        if (control.hid in setOf("trigger_l2", "trigger_r2")) {
            when (control.hid) {
                "trigger_l2" -> surface.setTrigger(if (pressed) 1f else 0f, isLeft = true)
                "trigger_r2" -> surface.setTrigger(if (pressed) 1f else 0f, isLeft = false)
            }
            return
        }
        val currentButtons = surface.snapshot().buttons
        val button = control.buttonBit()
        if (button != 0) {
            surface.setButtons(if (pressed) currentButtons or button else currentButtons and button.inv())
        }
    }

    private fun quadrantDirection(
        nx: Float,
        ny: Float,
        control: TouchControl,
    ): Int {
        val relX = nx - (control.x + control.w / 2)
        val relY = ny - (control.y + control.h / 2)
        val angle = Math.toDegrees(atan2(relX.toDouble(), -relY.toDouble())).toFloat()
        val sector =
            floor((angle + DPAD_HALF_SECTOR_DEGREES + DEGREES_PER_FULL_TURN) / DEGREES_PER_DPAD_SECTOR)
                .toInt() % DPAD_DIRECTIONS
        return when (sector) {
            DPAD_NORTH -> DPAD_NORTH
            DIAGONAL_NORTH_EAST_SECTOR -> DPAD_NORTH_EAST
            DPAD_EAST -> DPAD_EAST
            DIAGONAL_SOUTH_EAST_SECTOR -> DPAD_SOUTH_EAST
            DPAD_SOUTH -> DPAD_SOUTH
            DIAGONAL_SOUTH_WEST_SECTOR -> DPAD_SOUTH_WEST
            DPAD_WEST -> DPAD_WEST
            else -> DPAD_NORTH_WEST
        }
    }

    /** Hitbox = forma dibujada + margen de percepción (CR-068/CR-072);
     * en solape gana el motivo más pequeño (hitIndex). */
    private fun hitTest(
        px: Float,
        py: Float,
        view: View,
        profile: ControlProfile,
    ): HitTarget? {
        if (!controlsVisible) return null
        val width = view.width.toFloat().coerceAtLeast(1f)
        val height = view.height.toFloat().coerceAtLeast(1f)
        val marginPx = HITBOX_MARGIN_DP * view.resources.displayMetrics.density
        val index = hitIndex(profile, HitQuery(px, py, width, height, marginPx))
        return if (index >= 0) HitTarget(index, px / width, py / height) else null
    }

    private fun emit() {
        // Snapshot puro; la deduplicación y el `seq` los asigna la única
        // GamepadStateMachine, en PairingViewModel (SPEC-004 §6/R2).
        onInput(surface.snapshot())
    }

    private data class HitTarget(val index: Int, val nx: Float, val ny: Float)
}

private fun videoSurfaceContainer(view: View): View? {
    var current: ViewParent? = view.parent
    while (current != null) {
        if (current is ViewGroup) {
            current.findViewById<View>(com.twinpad.android.app.R.id.video_surface_container)?.let { return it }
        }
        current = current.parent
    }
    return null
}

private fun applyStick(
    surface: GamepadSurfaceState,
    control: TouchControl,
    nx: Float,
    ny: Float,
    pressed: Boolean,
) {
    val dx = if (pressed) (nx - (control.x + control.w / 2)) / (control.w / 2) else 0f
    val dy = if (pressed) (ny - (control.y + control.h / 2)) / (control.h / 2) else 0f
    val magnitude = sqrt(dx * dx + dy * dy)
    val radialScale = if (magnitude > 1f) 1f / magnitude else 1f
    surface.setStick(dx * radialScale, dy * radialScale, isLeft = control.hid == "stick_l")
}

private fun TouchControl.buttonBit(): Int =
    when (hid) {
        "button_a" -> ProtocolConstants.BUTTON_BIT_A
        "button_b" -> ProtocolConstants.BUTTON_BIT_B
        "button_x" -> ProtocolConstants.BUTTON_BIT_X
        "button_y" -> ProtocolConstants.BUTTON_BIT_Y
        "button_l1" -> ProtocolConstants.BUTTON_BIT_L1
        "button_r1" -> ProtocolConstants.BUTTON_BIT_R1
        "button_l3" -> ProtocolConstants.BUTTON_BIT_L3
        "button_r3" -> ProtocolConstants.BUTTON_BIT_R3
        "button_start" -> ProtocolConstants.BUTTON_BIT_START
        "button_select" -> ProtocolConstants.BUTTON_BIT_SELECT
        "button_extra_1" -> ProtocolConstants.BUTTON_BIT_EXTRA_1
        "button_extra_2" -> ProtocolConstants.BUTTON_BIT_EXTRA_2
        else -> 0
    }
