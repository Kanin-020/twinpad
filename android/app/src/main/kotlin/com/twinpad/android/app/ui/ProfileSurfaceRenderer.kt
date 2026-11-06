package com.twinpad.android.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.TouchControl
import kotlin.math.roundToInt

/** Argumentos de un frame: agrupados para respetar el límite de parámetros (ENG P4). */
internal data class DrawArgs(
    val canvas: Canvas,
    val profile: ControlProfile,
    val state: ProfileRenderState,
    val width: Float,
    val height: Float,
    val showControls: Boolean,
)

internal data class ProfileRenderState(
    val draggingIndex: Int,
    val activePointers: Map<Int, Int>,
    val pointerPositions: Map<Int, Pair<Float, Float>>,
)

/** Draws the profile controls independently from touch input. */
internal class ProfileSurfaceRenderer(context: Context) {
    private val buttonDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.touch_button, context.theme).mutate()
    private val plateDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.bg_control, context.theme).mutate()
    private val stickThumbDrawable =
        context.resources.getDrawable(com.twinpad.android.app.R.drawable.stick_thumb, context.theme).mutate()

    /** CR-077 v2: color del texto/chevron sobre cara clara — Charcoal Panel
     * de DESIGN.md («dark text on white controls»); Inter para las etiquetas. */
    private val labelColor =
        androidx.core.content.ContextCompat.getColor(
            context,
            com.twinpad.android.app.R.color.design_charcoal_panel,
        )
    private val labelTypeface: android.graphics.Typeface =
        androidx.core.content.res.ResourcesCompat.getFont(context, com.twinpad.android.app.R.font.inter)
            ?: DEFAULT_LABEL_TYPEFACE
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = TEXT_SIZE_SP * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            typeface = labelTypeface
        }
    private val chevronPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    fun draw(args: DrawArgs) {
        if (!args.showControls) return
        for ((index, control) in args.profile.controls.withIndex()) {
            drawControl(ControlFrame(args.canvas, index, control, args.state, args.width, args.height, NORMAL_ALPHA))
        }
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
                index == state.draggingIndex -> PRESSED_ALPHA
                else -> NORMAL_ALPHA
            }
        val frame = ControlFrame(canvas, index, control, state, width, height, alpha)
        when (control.kind) {
            ControlProfileCodec.KIND_DPAD -> drawDirectionalPad(canvas, control, width, height, alpha)
            ControlProfileCodec.KIND_STICK -> drawJoystick(frame)
            else -> drawButton(frame)
        }
        if (control.kind != ControlProfileCodec.KIND_DPAD) {
            // Etiqueta del control (refs: ~45 % de la cara del botón,
            // gris oscuro, letra normal); se autoescala con el tamaño.
            textPaint.textSize = minOf(control.w * width, control.h * height) * LABEL_SCALE
            textPaint.alpha = alpha
            textPaint.color = labelColor
            canvas.drawText(
                control.label,
                labelX(control, width),
                labelY(control, height, textPaint.textSize),
                textPaint,
            )
        }
    }

    /** Botón circular estilo referencia: halo oscuro + cara clara centrada. */
    private fun drawButton(frame: ControlFrame) {
        val control = frame.control
        val cx = (control.x + control.w / 2f) * frame.width
        val cy = (control.y + control.h / 2f) * frame.height
        val side = minOf(control.w * frame.width, control.h * frame.height)
        val faceHalf = side / 2f
        val haloHalf = faceHalf * BUTTON_HALO_RADIUS
        drawDrawable(
            frame.canvas,
            plateDrawable,
            DrawableFrame(
                (cx - haloHalf).roundToInt(),
                (cy - haloHalf).roundToInt(),
                (cx + haloHalf).roundToInt(),
                (cy + haloHalf).roundToInt(),
                frame.alpha,
            ),
        )
        drawDrawable(
            frame.canvas,
            buttonDrawable,
            DrawableFrame(
                (cx - faceHalf).roundToInt(),
                (cy - faceHalf).roundToInt(),
                (cx + faceHalf).roundToInt(),
                (cy + faceHalf).roundToInt(),
                frame.alpha,
                // CR-068: la animación de pulsado es SOLO del control activo —
                // el drawable de cara es compartido: con `draggingIndex >= 0`
                // todos los botones heredaban el estado de pulsado.
                frame.state.draggingIndex == frame.index,
            ),
        )
    }

    /** Stick estilo referencia: plato circular oscuro + pulgar claro. */
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
            plateDrawable,
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

    /** D-pad estilo referencia: plato circular oscuro, 4 botones-flecha
     * claros y puntos decorativos en las diagonales. */
    private fun drawDirectionalPad(
        canvas: Canvas,
        control: TouchControl,
        width: Float,
        height: Float,
        alpha: Int,
    ) {
        val centerX = (control.x + control.w / 2f) * width
        val centerY = (control.y + control.h / 2f) * height
        val radius = minOf(control.w * width, control.h * height) / 2f
        drawCircle(canvas, plateDrawable, Circle(centerX, centerY, radius), alpha)
        val buttonRadius = radius * DPAD_BUTTON_RADIUS
        val offset = radius * DPAD_BUTTON_OFFSET
        for (direction in DIRECTIONS) {
            val dx = direction.first
            val dy = direction.second
            val cx = centerX + dx * offset
            val cy = centerY + dy * offset
            drawChevron(canvas, Circle(cx, cy, buttonRadius), direction)
        }
        val dotRadius = radius * DPAD_DOT_RADIUS
        val dotOffset = radius * DPAD_DOT_OFFSET
        for (dot in DIAGONALS) {
            val cx = centerX + dot.first * dotOffset
            val cy = centerY + dot.second * dotOffset
            drawCircle(canvas, buttonDrawable, Circle(cx, cy, dotRadius), DPAD_DOT_ALPHA)
        }
    }

    /** Botón-flecha del D-pad: cara clara + chevron (sustituye la etiqueta). */
    private fun drawChevron(
        canvas: Canvas,
        circle: Circle,
        direction: Pair<Float, Float>,
    ) {
        drawCircle(canvas, buttonDrawable, circle, NORMAL_ALPHA)
        // Punta del chevron hacia la dirección; las alas parten de atrás.
        val dx = direction.first
        val dy = direction.second
        val radius = circle.radius
        val tipX = circle.cx + dx * radius * CHEVRON_TIP
        val tipY = circle.cy + dy * radius * CHEVRON_TIP
        val backX = circle.cx - dx * radius * CHEVRON_BACK
        val backY = circle.cy - dy * radius * CHEVRON_BACK
        val wingX = -dy
        val wingY = dx
        val wing = radius * CHEVRON_WING
        chevronPaint.strokeWidth = radius * CHEVRON_STROKE
        chevronPaint.color = labelColor
        canvas.drawLines(
            floatArrayOf(
                backX + wingX * wing,
                backY + wingY * wing,
                tipX,
                tipY,
                tipX,
                tipY,
                backX - wingX * wing,
                backY - wingY * wing,
            ),
            chevronPaint,
        )
    }

    /** Dibuja un drawable oval como círculo centrado en `circle`. */
    private fun drawCircle(
        canvas: Canvas,
        drawable: android.graphics.drawable.Drawable,
        circle: Circle,
        alpha: Int,
        pressed: Boolean = false,
    ) {
        drawDrawable(
            canvas,
            drawable,
            DrawableFrame(
                (circle.cx - circle.radius).roundToInt(),
                (circle.cy - circle.radius).roundToInt(),
                (circle.cx + circle.radius).roundToInt(),
                (circle.cy + circle.radius).roundToInt(),
                alpha,
                pressed,
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

    /** Círculo de estilo referencia: centro en pantalla + radio px. */
    private data class Circle(
        val cx: Float,
        val cy: Float,
        val radius: Float,
    )

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Tipo de las etiquetas (letra normal, sin negritas). */
        val DEFAULT_LABEL_TYPEFACE =
            android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)

        /** Ejes de flecha del D-pad: arriba, derecha, abajo, izquierda. */
        val DIRECTIONS = listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)

        /** Diagonales de los puntos decorativos del D-pad. */
        val DIAGONALS = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
    }
}

private const val DIMMED_ALPHA = 77
private const val PRESSED_ALPHA = 235
private const val NORMAL_ALPHA = 115
private const val DPAD_DOT_ALPHA = 75

/** Botones del D-pad: radio y distancia al centro (× radio del plato).
 * Radio 0.32, distancia 0.58: las flechas quedan separadas entre sí
 * (hueco 0.18·R en diagonal) sin salirse del plato (0.58 + 0.32 ≤ 1). */
private const val DPAD_BUTTON_RADIUS = 0.32f
private const val DPAD_BUTTON_OFFSET = 0.58f

/** Puntos decorativos en las diagonales (pulsación intermedia NE/SE/SW/NW):
 * algo más allá del punto medio entre dos flechas contiguas (0.41·R). */
private const val DPAD_DOT_RADIUS = 0.11f
private const val DPAD_DOT_OFFSET = 0.47f

/** Chevron: punta, retroceso, alas y grosor (× radio del botón del D-pad). */
private const val CHEVRON_TIP = 0.30f
private const val CHEVRON_BACK = 0.10f
private const val CHEVRON_WING = 0.42f
private const val CHEVRON_STROKE = 0.22f

private const val JOYSTICK_KNOB_SCALE = 0.72f
private const val BUTTON_HALO_RADIUS = 0.72f

/** Altura de la etiqueta como fracción del lado menor del control (refs). */
private const val LABEL_SCALE = 0.44f
private const val TEXT_SIZE_SP = 14f
private const val TEXT_SIZE_DIVISOR = 3
