package com.twinpad.android.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.TouchControl
import kotlin.math.roundToInt

internal data class ProfileRenderState(
    val selectedIndex: Int,
    val draggingIndex: Int,
    val editing: Boolean,
    val activePointers: Map<Int, Int>,
    val pointerPositions: Map<Int, Pair<Float, Float>>,
)

/** Draws profile controls independently from touch input and profile editing. */
internal class ProfileSurfaceRenderer(context: Context) {
    private val buttonDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.touch_button, context.theme).mutate()
    private val surfaceDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.bg_control, context.theme).mutate()
    private val stickBaseDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.stick_base, context.theme).mutate()
    private val stickThumbDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.stick_thumb, context.theme).mutate()
    private val selectionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * context.resources.displayMetrics.density
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = TEXT_SIZE_SP * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

    fun draw(
        canvas: Canvas,
        profile: ControlProfile,
        state: ProfileRenderState,
        width: Float,
        height: Float,
    ) {
        drawGamepadShell(canvas, width, height)
        for ((index, control) in profile.controls.withIndex()) {
            drawControl(ControlFrame(canvas, index, control, state, width, height, NORMAL_ALPHA))
        }
    }

    private fun drawGamepadShell(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        drawDrawable(
            canvas,
            surfaceDrawable,
            DrawableFrame(
                (width * GAMEPAD_SHELL_LEFT).roundToInt(),
                (height * GAMEPAD_SHELL_TOP).roundToInt(),
                (width * GAMEPAD_SHELL_RIGHT).roundToInt(),
                (height * GAMEPAD_SHELL_BOTTOM).roundToInt(),
                GAMEPAD_SHELL_ALPHA,
            ),
        )
    }

    private fun drawControl(frame: ControlFrame) {
        val canvas = frame.canvas
        val index = frame.index
        val control = frame.control
        val state = frame.state
        val width = frame.width
        val height = frame.height
        val alpha =
            when {
                control.hid.isEmpty() -> DIMMED_ALPHA
                index == state.draggingIndex && !state.editing -> PRESSED_ALPHA
                else -> NORMAL_ALPHA
            }
        val frame = ControlFrame(canvas, index, control, state, width, height, alpha)
        when (control.kind) {
            ControlProfileCodec.KIND_DPAD -> drawDirectionalPad(canvas, control, width, height, alpha)
            ControlProfileCodec.KIND_STICK -> drawJoystick(frame)
            else -> drawButton(frame)
        }
        textPaint.alpha = alpha
        textPaint.color = if (control.kind == ControlProfileCodec.KIND_STICK) WHITE else GAMEPAD_LABEL_DARK
        canvas.drawText(control.label, labelX(control, width), labelY(control, height, textPaint.textSize), textPaint)
        if (state.editing && index == state.selectedIndex) {
            selectionPaint.color = SELECTION_COLOR
            drawRoundedRect(canvas, control, width, height, selectionPaint)
        }
    }

    private fun drawButton(frame: ControlFrame) {
        val control = frame.control
        drawDrawable(
            frame.canvas,
            buttonDrawable,
            DrawableFrame(
                (control.x * frame.width).roundToInt(),
                (control.y * frame.height).roundToInt(),
                ((control.x + control.w) * frame.width).roundToInt(),
                ((control.y + control.h) * frame.height).roundToInt(),
                frame.alpha,
                frame.state.draggingIndex >= 0 && !frame.state.editing,
            ),
        )
    }

    private fun drawJoystick(frame: ControlFrame) {
        val canvas = frame.canvas
        val index = frame.index
        val control = frame.control
        val state = frame.state
        val width = frame.width
        val height = frame.height
        val alpha = frame.alpha
        val centerX = (control.x + control.w / 2f) * width
        val centerY = (control.y + control.h / 2f) * height
        val radius = minOf(control.w * width, control.h * height) / 2f
        drawDrawable(
            canvas,
            stickBaseDrawable,
            DrawableFrame(
                (centerX - radius).roundToInt(),
                (centerY - radius).roundToInt(),
                (centerX + radius).roundToInt(),
                (centerY + radius).roundToInt(),
                alpha,
            ),
        )
        val pointer =
            state.activePointers.entries.firstOrNull { it.value == index }?.key
                ?.let(state.pointerPositions::get)
        val offsetX = ((pointer?.first ?: control.x + control.w / 2f) - (control.x + control.w / 2f)) * width
        val offsetY = ((pointer?.second ?: control.y + control.h / 2f) - (control.y + control.h / 2f)) * height
        val thumbRadius = radius * JOYSTICK_KNOB_SCALE
        drawDrawable(
            canvas,
            stickThumbDrawable,
            DrawableFrame(
                (centerX + offsetX - thumbRadius).roundToInt(),
                (centerY + offsetY - thumbRadius).roundToInt(),
                (centerX + offsetX + thumbRadius).roundToInt(),
                (centerY + offsetY + thumbRadius).roundToInt(),
                alpha,
            ),
        )
    }

    private fun drawDirectionalPad(
        canvas: Canvas,
        control: TouchControl,
        width: Float,
        height: Float,
        alpha: Int,
    ) {
        val left = control.x * width
        val top = control.y * height
        val padWidth = control.w * width
        val padHeight = control.h * height
        val thickness = minOf(padWidth, padHeight) * DPAD_BAR_THICKNESS
        drawDrawable(
            canvas,
            surfaceDrawable,
            DrawableFrame(
                (left + (padWidth - thickness) / 2f).roundToInt(),
                top.roundToInt(),
                (left + (padWidth + thickness) / 2f).roundToInt(),
                (top + padHeight).roundToInt(),
                alpha,
            ),
        )
        drawDrawable(
            canvas,
            surfaceDrawable,
            DrawableFrame(
                left.roundToInt(),
                (top + (padHeight - thickness) / 2f).roundToInt(),
                (left + padWidth).roundToInt(),
                (top + (padHeight + thickness) / 2f).roundToInt(),
                alpha,
            ),
        )
    }

    private fun drawDrawable(
        canvas: Canvas,
        drawable: android.graphics.drawable.Drawable,
        frame: DrawableFrame,
    ) {
        drawable.state = if (frame.pressed) intArrayOf(android.R.attr.state_pressed) else intArrayOf()
        drawable.alpha = frame.alpha
        drawable.setBounds(frame.left, frame.top, frame.right, frame.bottom)
        drawable.draw(canvas)
    }

    private fun drawRoundedRect(
        canvas: Canvas,
        control: TouchControl,
        width: Float,
        height: Float,
        paint: Paint,
    ) {
        canvas.drawRoundRect(
            control.x * width,
            control.y * height,
            (control.x + control.w) * width,
            (control.y + control.h) * height,
            CORNER_RADIUS_PX,
            CORNER_RADIUS_PX,
            paint,
        )
    }

    private fun labelX(
        control: TouchControl,
        width: Float,
    ): Float = (control.x + control.w / 2) * width

    private fun labelY(
        control: TouchControl,
        height: Float,
        textSize: Float,
    ): Float = (control.y + control.h / 2) * height + textSize / TEXT_SIZE_DIVISOR

    private data class ControlFrame(
        val canvas: Canvas,
        val index: Int,
        val control: TouchControl,
        val state: ProfileRenderState,
        val width: Float,
        val height: Float,
        val alpha: Int,
    )

    private data class DrawableFrame(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val alpha: Int,
        val pressed: Boolean = false,
    )

    private companion object {
        const val SELECTION_COLOR = 0xFF03A9F4.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

private const val DIMMED_ALPHA = 77
private const val PRESSED_ALPHA = 153
private const val NORMAL_ALPHA = 204
private const val CORNER_RADIUS_PX = 18f
private const val DPAD_BAR_THICKNESS = 0.32f
private const val JOYSTICK_KNOB_SCALE = 0.72f
private const val GAMEPAD_SHELL_ALPHA = 36
private const val GAMEPAD_SHELL_LEFT = 0.02f
private const val GAMEPAD_SHELL_TOP = 0.28f
private const val GAMEPAD_SHELL_RIGHT = 0.98f
private const val GAMEPAD_SHELL_BOTTOM = 0.98f
private const val GAMEPAD_LABEL_DARK = 0xFF263238.toInt()
private const val TEXT_SIZE_SP = 14f
private const val TEXT_SIZE_DIVISOR = 3
