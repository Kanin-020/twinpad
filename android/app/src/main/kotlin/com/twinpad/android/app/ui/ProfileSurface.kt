package com.twinpad.android.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.hid.GamepadStateMachine
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.ControlProfileStore
import com.twinpad.android.app.wire.InputReport
import kotlinx.coroutines.launch

/** Editable touch surface that maps profile gestures to the Twinpad gamepad signature. */
class ProfileSurface(
    context: Context,
    private val onInput: (InputReport) -> Unit,
) : View(context) {
    private val gamepadSurface = GamepadSurfaceState()
    private val gamepad = GamepadStateMachine()
    private val store = ControlProfileStore(context)
    private val renderer = ProfileSurfaceRenderer(context)
    private val touchController = ProfileTouchController(gamepadSurface, gamepad, onInput)
    private val gyroController =
        ProfileGyroController { orientation ->
            gamepadSurface.setGyro(orientation)
            emit()
        }
    private var activeGameId = ""
    private var profileLoadGeneration = 0
    private var controlCounter = 0

    var profile: ControlProfile = ControlProfileCodec.defaultProfile()
        private set

    var editing = false
        private set

    init {
        gamepad.onChannelReady()?.let(onInput)
    }

    fun updateGyro(orientationTurns: List<Int>) = gyroController.update(orientationTurns)

    fun setGyroSensitivity(sensitivity: Float) = gyroController.setSensitivity(sensitivity)

    fun calibrateGyro() = gyroController.recalibrate()

    fun loadProfile(gameId: String) {
        activeGameId = gameId
        val generation = ++profileLoadGeneration
        val scope = viewLifecycleScope() ?: return
        scope.launch {
            val loaded = if (gameId.isEmpty()) null else store.load(gameId)
            if (generation != profileLoadGeneration || editing) return@launch
            profile = loaded ?: ControlProfileCodec.defaultProfile(gameId)
            invalidate()
        }
    }

    fun addControl(kind: String) {
        if (!editing || profile.controls.size >= ControlProfileCodec.MAX_CONTROLS) return
        val control = createControl(kind, ++controlCounter)
        touchController.selectControl(profile.controls.size)
        profile = profile.copy(controls = profile.controls + control)
        invalidate()
    }

    fun availableAssignments(): List<String> =
        if (editing) {
            touchController.availableAssignments(profile)
        } else {
            emptyList()
        }

    fun assignSelected(hid: String) {
        if (!editing) return
        profile = profile.assignControl(touchController.selectedControlIndex, hid)
        invalidate()
    }

    fun deleteSelectedControl() {
        if (!editing) return
        profile = profile.deleteSelected(touchController.selectedControlIndex)
        touchController.selectControl(-1)
        invalidate()
    }

    fun toggleEditing() {
        editing = !editing
        touchController.toggleEditing()
        if (!editing) {
            profile = profile.copy(game = activeGameId)
            viewLifecycleScope()?.launch { store.save(profile) }
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = touchController.onTouch(event, this, profile, editing, ::moveControl)
        if (handled) invalidate()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            canvas = canvas,
            profile = profile,
            state = touchController.renderState(editing),
            width = width.toFloat(),
            height = height.toFloat(),
        )
    }

    private fun moveControl(
        index: Int,
        dxPx: Float,
        dyPx: Float,
    ) {
        if (index !in profile.controls.indices || width == 0 || height == 0) return
        val control = profile.controls[index]
        val moved =
            control.copy(
                x = (control.x + dxPx / width).coerceIn(0f, 1f - control.w),
                y = (control.y + dyPx / height).coerceIn(0f, 1f - control.h),
            )
        profile =
            profile.copy(
                controls =
                    profile.controls.mapIndexed { controlIndex, old ->
                        if (controlIndex == index) moved else old
                    },
            )
        invalidate()
    }

    private fun emit() {
        gamepad.onElementChanged(gamepadSurface.snapshot())?.let(onInput)
    }

    private fun viewLifecycleScope(): kotlinx.coroutines.CoroutineScope? = findViewTreeLifecycleOwner()?.lifecycleScope

    private fun createControl(
        kind: String,
        index: Int,
    ): com.twinpad.android.app.surface.TouchControl {
        val hid =
            when (kind) {
                ControlProfileCodec.KIND_STICK -> "stick_l"
                ControlProfileCodec.KIND_DPAD -> "dpad"
                else -> "button_a"
            }
        return com.twinpad.android.app.surface.TouchControl(
            id = "custom_$index",
            kind = kind,
            label =
                when (kind) {
                    ControlProfileCodec.KIND_STICK -> "Stick"
                    ControlProfileCodec.KIND_DPAD -> "D-pad"
                    else -> "Button"
                },
            hid = hid,
            x = DEFAULT_CONTROL_ORIGIN,
            y = DEFAULT_CONTROL_ORIGIN,
            w = ControlProfileCodec.DEFAULT_SIZE,
            h = ControlProfileCodec.DEFAULT_SIZE,
        )
    }

    private companion object {
        const val DEFAULT_CONTROL_ORIGIN = 0.4f
    }
}

private fun ControlProfile.deleteSelected(index: Int): ControlProfile {
    if (index !in controls.indices) return this
    return copy(controls = controls.filterIndexed { controlIndex, _ -> controlIndex != index })
}

private fun ControlProfile.assignControl(
    index: Int,
    hid: String,
): ControlProfile {
    if (index !in controls.indices) return this
    return copy(
        controls =
            controls.mapIndexed { controlIndex, control ->
                if (controlIndex == index) control.copy(hid = hid) else control
            },
    )
}
