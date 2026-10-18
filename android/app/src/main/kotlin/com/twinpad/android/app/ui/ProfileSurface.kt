package com.twinpad.android.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.ControlProfileStore
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import kotlinx.coroutines.launch

/** Bits de botones A/B/X/Y (SPEC-004 §4; máscara 0–3). */
private const val BIT_A: Int = 1 shl 0
private const val BIT_B: Int = 1 shl 1
private const val BIT_X: Int = 1 shl 2
private const val BIT_Y: Int = 1 shl 3

/** Direcciones del D-pad (SPEC-004 §4: 0=N … 8=neutro). */
private const val DPAD_N = 0
private const val DPAD_E = 2
private const val DPAD_S = 4
private const val DPAD_W = 6
private const val DPAD_NEUTRAL = 8

/**
 * Superficie táctil configurable sobre el sistema de Views (SPEC-008 Draft,
 * layouts XML editables en Android Studio — pedido del owner): dibuja los
 * controles del perfil en coordenadas normalizadas 0…1; en modo uso (S1)
 * alimenta [GamepadSurfaceState] y envía `input_report` (los sin asignar se
 * atenúan y NO envían, AC-3); en modo edición (S0) arrastra para mover, sin
 * enviar nada (R3). El perfil se persiste vía [ControlProfileStore] (AC-2).
 */
class ProfileSurface(
    context: Context,
    private val onInput: (InputReport) -> Unit,
) : View(context) {
    /** Máquina del mapeo puro (ENG P10, test JVM ya existente). */
    private val surface = GamepadSurfaceState()

    /** Máquina de seq (SPEC-004 R2). */
    private val gamepad = com.twinpad.android.app.hid.GamepadStateMachine()

    private val store = ControlProfileStore(context)

    /** Perfil activo (default hasta cargar/guardar por juego, AC-2). */
    var profile: ControlProfile = ControlProfileCodec.defaultProfile()
        private set

    /** Modo edición (S0): no envía; arrastra para mover (R3). */
    var editing = false
        private set

    private var selectedIdx: Int = -1
    private var draggingIdx: Int = -1
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var drcTouchActive = false

    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = TEXT_SIZE_SP * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

    init {
        // H1 (§6): al estar el canal entre READY, el reporte de reposo salió.
        gamepad.onChannelReady()?.let(onInput)
    }

    /** Actualiza yaw/pitch/roll absolutos; una muestra se agrega al reporte completo. */
    fun updateGyro(orientationTurns: List<Int>) {
        surface.setGyro(orientationTurns)
        emit()
    }

    /** Alternar edición/uso; al salir se guarda el perfil (§5, AC-1). */
    fun toggleEditing() {
        editing = !editing
        selectedIdx = -1
        if (!editing) {
            viewLifecycleScope()?.launch { store.save(profile) }
        }
        invalidate()
    }

    /** Scope del LifecycleOwner de esta vista (para el store). */
    private fun viewLifecycleScope(): kotlinx.coroutines.CoroutineScope? = findViewTreeLifecycleOwner()?.lifecycleScope

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val nx = event.x / width
        val ny = event.y / height
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(nx, ny, event)
            MotionEvent.ACTION_MOVE -> onMove(nx, ny, event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp()
            else -> false
        }
    }

    private fun onDown(
        nx: Float,
        ny: Float,
        event: MotionEvent,
    ): Boolean {
        val idx = hitTest(nx, ny)
        draggingIdx = idx
        if (idx < 0 && !editing) {
            drcTouchActive = true
            updateDrcTouch(nx, ny, pressed = true)
            emit()
        }
        if (editing) {
            selectedIdx = idx
            dragLastX = event.x
            dragLastY = event.y
        } else {
            applyControl(idx, nx, ny, pressed = true)
        }
        invalidate()
        return true
    }

    private fun onMove(
        nx: Float,
        ny: Float,
        event: MotionEvent,
    ): Boolean {
        if (drcTouchActive && draggingIdx < 0 && !editing) {
            updateDrcTouch(nx, ny, pressed = true)
            emit()
            return true
        }
        if (draggingIdx < 0) return false
        if (editing) {
            moveSelected(event.x - dragLastX, event.y - dragLastY)
            dragLastX = event.x
            dragLastY = event.y
        } else {
            applyControl(draggingIdx, nx, ny, pressed = true)
        }
        return true
    }

    private fun onUp(): Boolean {
        if (drcTouchActive) {
            drcTouchActive = false
            surface.setTouch(surface.snapshot().touchX, surface.snapshot().touchY, pressed = false)
            emit()
        } else if (!editing && draggingIdx >= 0) {
            applyControl(draggingIdx, 0f, 0f, pressed = false)
        }
        draggingIdx = -1
        invalidate()
        return true
    }

    private fun findContentRoot(): ViewGroup? {
        var current: ViewParent? = parent
        while (current != null && current !is ViewGroup) current = current.parent
        return current as? ViewGroup
    }

    /** Actualiza el toque DRC en coordenadas normalizadas de la imagen visible. */
    private fun updateDrcTouch(
        nx: Float,
        ny: Float,
        pressed: Boolean,
    ) {
        val image = findContentRoot()?.findViewById<ImageView>(com.twinpad.android.app.R.id.video_view) ?: return
        val drawable = image.drawable ?: return
        val bitmapWidth = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val bitmapHeight = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val viewWidth = image.width.toFloat().coerceAtLeast(1f)
        val viewHeight = image.height.toFloat().coerceAtLeast(1f)
        val imageLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        image.getLocationOnScreen(imageLocation)
        getLocationOnScreen(surfaceLocation)
        val pointerX = nx * width - (imageLocation[0] - surfaceLocation[0])
        val pointerY = ny * height - (imageLocation[1] - surfaceLocation[1])
        val scale = minOf(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val shownWidth = bitmapWidth * scale
        val shownHeight = bitmapHeight * scale
        val left = (viewWidth - shownWidth) / 2f
        val top = (viewHeight - shownHeight) / 2f
        val localX = pointerX - left
        val localY = pointerY - top
        if (!(localX in 0f..shownWidth && localY in 0f..shownHeight)) return
        val x = (localX / shownWidth).coerceIn(0f, 1f)
        val y = (localY / shownHeight).coerceIn(0f, 1f)
        surface.setTouch(
            (x * ProtocolConstants.TOUCH_MAX).toInt(),
            (y * ProtocolConstants.TOUCH_MAX).toInt(),
            pressed,
        )
    }

    /** Aplica el gesto del control al estado de la Signature (S1, §7). */
    private fun applyControl(
        idx: Int,
        nx: Float,
        ny: Float,
        pressed: Boolean,
    ) {
        val c = profile.controls.getOrNull(idx) ?: return
        if (!editing) {
            // En modo edición raramente se invoca; S1 activo siempre.
            when {
                c.kind == ControlProfileCodec.KIND_STICK ->
                    applyStick(c, nx, ny, pressed)
                c.hid == "dpad" ->
                    surface.setDpad(if (pressed) quadrantDirection(nx, ny, c) else DPAD_NEUTRAL)
                else ->
                    applyButtonOrTrigger(c, pressed)
            }
            emit()
        }
    }

    /** Stick analógico: centro del control en (0,0), ejes −1…1. */
    private fun applyStick(
        c: com.twinpad.android.app.surface.TouchControl,
        nx: Float,
        ny: Float,
        pressed: Boolean,
    ) {
        val dx = if (pressed) (nx - (c.x + c.w / 2)) / (c.w / 2) else 0f
        val dy = if (pressed) (ny - (c.y + c.h / 2)) / (c.h / 2) else 0f
        surface.setStick(dx, dy, isLeft = c.hid == "stick_l")
    }

    /** Botones digitales y triggers analógicos al presionar/soltar. */
    private fun applyButtonOrTrigger(
        c: com.twinpad.android.app.surface.TouchControl,
        pressed: Boolean,
    ) {
        when (c.hid) {
            "trigger_l2" -> surface.setTrigger(if (pressed) 1f else 0f, isLeft = true)
            "trigger_r2" -> surface.setTrigger(if (pressed) 1f else 0f, isLeft = false)
            else -> {
                val currentButtons = surface.snapshot().buttons
                val button = buttonBit(c.hid)
                surface.setButtons(
                    if (pressed) currentButtons or button else currentButtons and button.inv(),
                )
            }
        }
    }

    /** Dir. del D-pad según el cuadrante del toque (0=N en sentido horario). */
    private fun quadrantDirection(
        nx: Float,
        ny: Float,
        c: com.twinpad.android.app.surface.TouchControl,
    ): Int {
        val relX = nx - (c.x + c.w / 2)
        val relY = ny - (c.y + c.h / 2)
        return when {
            kotlin.math.abs(relX) > kotlin.math.abs(relY) && relX < 0 -> DPAD_W
            kotlin.math.abs(relX) > kotlin.math.abs(relY) -> DPAD_E
            relY < 0 -> DPAD_N
            else -> DPAD_S
        }
    }

    private fun buttonBit(hid: String): Int =
        when (hid) {
            "button_a" -> BIT_A
            "button_b" -> BIT_B
            "button_x" -> BIT_X
            "button_y" -> BIT_Y
            else -> 0
        }

    private fun emit() {
        gamepad.onElementChanged(surface.snapshot())?.let(onInput)
    }

    /** Mueve el control seleccionado en coords normalizadas (§4). */
    private fun moveSelected(
        dxPx: Float,
        dyPx: Float,
    ) {
        if (selectedIdx < 0) return
        val c = profile.controls[selectedIdx]
        val moved =
            c.copy(
                x = (c.x + dxPx / width).coerceIn(0f, 1f - c.w),
                y = (c.y + dyPx / height).coerceIn(0f, 1f - c.h),
            )
        updateControl(moved)
    }

    /** Reemplaza el control seleccionado y repinta. */
    private fun updateControl(moved: com.twinpad.android.app.surface.TouchControl) {
        profile =
            profile.copy(
                controls =
                    profile.controls.mapIndexed { i, old ->
                        if (i == selectedIdx) moved else old
                    },
            )
        invalidate()
    }

    private fun hitTest(
        nx: Float,
        ny: Float,
    ): Int =
        profile.controls.indexOfFirst { c ->
            nx >= c.x && nx <= c.x + c.w && ny >= c.y && ny <= c.y + c.h
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for ((i, c) in profile.controls.withIndex()) {
            drawControl(canvas, i, c, w, h)
        }
    }

    /** Dibuja un control: rect redondeado, etiqueta y selección en edición. */
    private fun drawControl(
        canvas: Canvas,
        idx: Int,
        c: com.twinpad.android.app.surface.TouchControl,
        w: Float,
        h: Float,
    ) {
        val alpha =
            when {
                c.hid.isEmpty() -> DIMMED_ALPHA // sin asignación: atenuado (AC-3)
                idx == draggingIdx && !editing -> PRESSED_ALPHA
                else -> NORMAL_ALPHA
            }
        controlPaint.alpha = alpha
        controlPaint.color = colorFor(c)
        drawRoundedRect(canvas, c, w, h, controlPaint)
        textPaint.alpha = alpha
        canvas.drawText(c.label, labelX(c, w), labelY(c, h, textPaint.textSize), textPaint)
        if (editing && idx == selectedIdx) {
            selectionPaint.color = SELECTION_COLOR
            drawRoundedRect(canvas, c, w, h, selectionPaint)
        }
    }

    /** Color por tipo (editable en drawables XML cuando aplique). */
    private fun colorFor(c: com.twinpad.android.app.surface.TouchControl): Int =
        when (c.kind) {
            ControlProfileCodec.KIND_STICK -> 0xFF8D6E63.toInt()
            ControlProfileCodec.KIND_DPAD -> 0xFF64B5F6.toInt()
            else -> 0xFFB0BEC5.toInt()
        }

    private companion object {
        /** Color de selección (azul material). */
        const val SELECTION_COLOR = 0xFF03A9F4.toInt()
    }
}

/** Alphas de dibujado (0–255; detekt: sin números mágicos). */
private const val DIMMED_ALPHA = 77
private const val PRESSED_ALPHA = 153
private const val NORMAL_ALPHA = 204

/** Radio de las esquinas de los controles (px). */
private const val CORNER_RADIUS_PX = 12f

/** Tamaño del texto de la etiqueta (sp). */
private const val TEXT_SIZE_SP = 14f

/** Divisor del centrado vertical del texto. */
private const val TEXT_SIZE_DIVISOR = 3

/** X centrado de la etiqueta del control (helper de dibujado). */
private fun labelX(
    c: com.twinpad.android.app.surface.TouchControl,
    w: Float,
): Float = (c.x + c.w / 2) * w

/** Y centrado (1/3 del tamaño) de la etiqueta (helper de dibujado). */
private fun labelY(
    c: com.twinpad.android.app.surface.TouchControl,
    h: Float,
    textSize: Float,
): Float = (c.y + c.h / 2) * h + textSize / TEXT_SIZE_DIVISOR

/** Rect redondeado del control en px (helper de dibujado). */
private fun drawRoundedRect(
    canvas: Canvas,
    c: com.twinpad.android.app.surface.TouchControl,
    w: Float,
    h: Float,
    paint: Paint,
) {
    canvas.drawRoundRect(
        c.x * w,
        c.y * h,
        (c.x + c.w) * w,
        (c.y + c.h) * h,
        CORNER_RADIUS_PX,
        CORNER_RADIUS_PX,
        paint,
    )
}
