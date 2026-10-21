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
private const val DIAGONAL_NORTH_EAST_SECTOR = 1
private const val DIAGONAL_SOUTH_EAST_SECTOR = 3
private const val DIAGONAL_SOUTH_WEST_SECTOR = 5

/** Routes touch gestures to the current profile and gamepad snapshot. */
internal class ProfileTouchController(
    private val surface: GamepadSurfaceState,
    private val gamepad: com.twinpad.android.app.hid.GamepadStateMachine,
    private val onInput: (InputReport) -> Unit,
) {
    private val activePointers = linkedMapOf<Int, Int>()
    private val pointerPositions = linkedMapOf<Int, Pair<Float, Float>>()
    private var drcTouchActive = false
    private var drcPointerId: Int? = null
    private var selectedIndex = -1
    private var draggingIndex = -1
    private var dragLastX = 0f
    private var dragLastY = 0f

    fun renderState(editing: Boolean) =
        ProfileRenderState(
            selectedIndex,
            draggingIndex,
            editing,
            activePointers,
            pointerPositions,
        )

    val selectedControlIndex: Int
        get() = selectedIndex

    fun selectControl(index: Int) {
        selectedIndex = index
    }

    fun availableAssignments(profile: ControlProfile): List<String> {
        val control = profile.controls.getOrNull(selectedIndex) ?: return emptyList()
        return when (control.kind) {
            ControlProfileCodec.KIND_STICK -> listOf("stick_l", "stick_r", "")
            ControlProfileCodec.KIND_DPAD -> listOf("dpad", "")
            else -> ControlProfileCodec.HID_ELEMENTS.filterNot { it == "dpad" || it.startsWith("stick_") } + ""
        }
    }

    fun onTouch(
        event: MotionEvent,
        view: View,
        profile: ControlProfile,
        editing: Boolean,
        moveSelected: (Int, Float, Float) -> Unit,
    ): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onPointerDown(event, view, profile, editing)
            MotionEvent.ACTION_MOVE -> onPointersMove(event, view, profile, editing, moveSelected)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onPointerUp(event, profile, editing)
            MotionEvent.ACTION_CANCEL -> cancelPointers(profile, editing)
            else -> false
        }

    fun toggleEditing() {
        selectedIndex = -1
        draggingIndex = -1
    }

    private fun onPointerDown(
        event: MotionEvent,
        view: View,
        profile: ControlProfile,
        editing: Boolean,
    ): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        val nx = event.getX(index) / view.width
        val ny = event.getY(index) / view.height
        val target = hitTest(nx, ny, profile)
        if (target == null) return beginDrcTouch(pointerId, nx, ny, editing, view)
        if (!canStartControlPointer(target.index, profile)) return false
        activePointers[pointerId] = target.index
        pointerPositions[pointerId] = target.nx to target.ny
        draggingIndex = target.index
        if (editing) {
            selectedIndex = target.index
            dragLastX = event.getX(index)
            dragLastY = event.getY(index)
        } else {
            applyControl(target.index, target.nx, target.ny, true, profile)
        }
        view.invalidate()
        return true
    }

    private fun beginDrcTouch(
        pointerId: Int,
        nx: Float,
        ny: Float,
        editing: Boolean,
        view: View,
    ): Boolean {
        if (editing || drcPointerId != null) return false
        drcPointerId = pointerId
        drcTouchActive = true
        updateDrcTouch(nx, ny, true, view)
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
        editing: Boolean,
        moveSelected: (Int, Float, Float) -> Unit,
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
            updateControlPointer(motion, profile, editing, moveSelected)
        }
        return true
    }

    private fun updateControlPointer(
        motion: TouchPointerMotion,
        profile: ControlProfile,
        editing: Boolean,
        moveSelected: (Int, Float, Float) -> Unit,
    ) {
        val controlIndex = activePointers[motion.pointerId] ?: return
        val control = profile.controls[controlIndex]
        val (adjustedX, adjustedY) =
            adjustDpadPosition(motion.normalizedX, motion.normalizedY, control)
        pointerPositions[motion.pointerId] = adjustedX to adjustedY
        if (editing) {
            selectedIndex = controlIndex
            moveSelected(
                controlIndex,
                motion.event.getX(motion.pointerIndex) - dragLastX,
                motion.event.getY(motion.pointerIndex) - dragLastY,
            )
            dragLastX = motion.event.getX(motion.pointerIndex)
            dragLastY = motion.event.getY(motion.pointerIndex)
        } else {
            applyControl(controlIndex, adjustedX, adjustedY, true, profile)
        }
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
        editing: Boolean,
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
        if (!editing) releaseControl(controlIndex, profile)
        draggingIndex = activePointers.values.lastOrNull() ?: -1
        return true
    }

    private fun cancelPointers(
        profile: ControlProfile,
        editing: Boolean,
    ): Boolean {
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
        if (!editing) pressedControls.forEach { releaseControl(it, profile) }
        draggingIndex = -1
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

    private fun updateDrcTouch(
        nx: Float,
        ny: Float,
        pressed: Boolean,
        view: View,
    ) {
        val videoSurface = videoSurfaceContainer(view) ?: return
        val viewWidth = videoSurface.width.toFloat().coerceAtLeast(1f)
        val viewHeight = videoSurface.height.toFloat().coerceAtLeast(1f)
        val viewLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        videoSurface.getLocationOnScreen(viewLocation)
        view.getLocationOnScreen(surfaceLocation)
        val pointerX = nx * view.width - (viewLocation[0] - surfaceLocation[0])
        val pointerY = ny * view.height - (viewLocation[1] - surfaceLocation[1])
        val x = (pointerX / viewWidth).coerceIn(0f, 1f)
        val y = (pointerY / viewHeight).coerceIn(0f, 1f)
        surface.setTouch(
            (x * ProtocolConstants.TOUCH_MAX).roundToInt(),
            (y * ProtocolConstants.TOUCH_MAX).roundToInt(),
            pressed,
        )
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

    private fun hitTest(
        nx: Float,
        ny: Float,
        profile: ControlProfile,
    ): HitTarget? {
        val index =
            profile.controls.indexOfFirst { control ->
                nx >= control.x && nx <= control.x + control.w && ny >= control.y && ny <= control.y + control.h
            }
        val dpadIndex = profile.controls.indexOfFirst { it.kind == ControlProfileCodec.KIND_DPAD }
        val dpad = profile.controls.getOrNull(dpadIndex)
        return when {
            index >= 0 -> HitTarget(index, nx, ny)
            dpad == null -> null
            else -> hitTestDpad(nx, ny, dpadIndex, dpad)
        }
    }

    private fun hitTestDpad(
        nx: Float,
        ny: Float,
        index: Int,
        dpad: TouchControl,
    ): HitTarget? {
        val centerX = dpad.x + dpad.w / 2f
        val centerY = dpad.y + dpad.h / 2f
        val dx = (nx - centerX) / dpad.w
        val dy = (ny - centerY) / dpad.h
        return if (sqrt(dx * dx + dy * dy) <= DIGITAL_DPAD_RADIUS_SCALE) {
            HitTarget(index, centerX + dx * dpad.w, centerY + dy * dpad.h)
        } else {
            null
        }
    }

    private fun emit() {
        gamepad.onElementChanged(surface.snapshot())?.let(onInput)
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
