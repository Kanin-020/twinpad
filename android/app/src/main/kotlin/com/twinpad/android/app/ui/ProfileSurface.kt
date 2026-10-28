package com.twinpad.android.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.hid.PhysicalGamepadAlgo
import com.twinpad.android.app.hid.PhysicalMotion
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.ControlProfileStore
import com.twinpad.android.app.surface.applyFaceDistribution
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.MotionInput
import kotlinx.coroutines.launch

/** Editable touch surface that maps profile gestures to the Twinpad gamepad signature. */
class ProfileSurface(
    context: Context,
    private val onInput: (InputReport) -> Unit,
) : View(context) {
    private val gamepadSurface = GamepadSurfaceState()

    /** Mando físico conectado al teléfono (CR-028): su estado se fusiona
     * con la superficie táctil al componer cada reporte (SPEC-004). */
    private val physicalGamepad = PhysicalGamepadAlgo()
    private val store = ControlProfileStore(context)
    private val renderer = ProfileSurfaceRenderer(context)
    private val touchController =
        ProfileTouchController(
            gamepadSurface,
            // CR-028: las emisiones táctiles también pasan por la fusión con
            // el mando físico; mismo camino que las del movimiento.
            { report -> onInput(physicalGamepad.fusedWith(report)) },
        ) { videoContentRect }
    var videoContentRect: VideoContentRect = VideoContentRect(0f, 0f, 1f, 1f)

    /**
     * Oculta solo los botones táctiles. La superficie sigue recibiendo toques
     * para la pantalla DRC (sin esto, ocultar la vista también dejaba de responder).
     */
    var showControls: Boolean = true
        set(value) {
            field = value
            touchController.controlsVisible = value || editing
            invalidate()
        }
    private var motionSample: MotionInput? = null
    private var activeGameId = ""
    private var profileLoadGeneration = 0
    private var controlCounter = 0

    var profile: ControlProfile = ControlProfileCodec.defaultProfile()
        private set

    var editing = false
        private set

    fun updateMotion(sample: MotionInput?) {
        if (sample == motionSample) return
        motionSample = sample
        gamepadSurface.setMotion(sample)
        emit()
    }

    fun loadProfile(gameId: String) {
        activeGameId = gameId
        val generation = ++profileLoadGeneration
        val scope = viewLifecycleScope() ?: return
        scope.launch {
            val loaded = if (gameId.isEmpty()) null else store.load(gameId)
            if (generation != profileLoadGeneration || editing) return@launch
            profile =
                applyFaceDistribution(
                    loaded ?: ControlProfileCodec.defaultProfile(gameId),
                    nintendoFaceLayout,
                )
            invalidate()
        }
    }

    /** CR-032: distribución Nintendo de los botones de cara (táctil y
     * física): recoloca A derecha/B abajo/X arriba/Y izquierda en el
     * perfil activo y activa el intercambio A↔B, X↔Y del mando físico;
     * apagar devuelve la posición Xbox/Play canónica del perfil
     * cargado. */
    var nintendoFaceLayout: Boolean = false
        private set

    fun setNintendoFaceLayout(enabled: Boolean) {
        if (enabled == nintendoFaceLayout) return
        nintendoFaceLayout = enabled
        physicalGamepad.nintendoFaceDistribution = enabled
        profile = applyFaceDistribution(profile, enabled)
        invalidate()
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
        touchController.controlsVisible = showControls || editing
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
            DrawArgs(
                canvas = canvas,
                profile = profile,
                state = touchController.renderState(editing),
                width = width.toFloat(),
                height = height.toFloat(),
                showControls = showControls || editing,
            ),
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

    /** CR-028: tecla del mando físico en keycode de plataforma. Devuelve
     * `true` solo si el elemento de la Signature cambió. */
    fun onPhysicalKey(
        keyCode: Int,
        pressed: Boolean,
    ): Boolean {
        val changed = physicalGamepad.onKey(keyCode, pressed)
        if (changed) emit()
        return changed
    }

    /** CR-028: ejes y hat del mando físico (valores de `MotionEvent`). */
    fun onPhysicalMotion(motion: PhysicalMotion) {
        physicalGamepad.onMotion(motion)
        emit()
    }

    /** CR-028: `true` si el keycode pertenece a la Signature. */
    fun handlesPhysicalKey(keyCode: Int): Boolean = physicalGamepad.handlesKey(keyCode)

    private fun emit() {
        // Snapshot puro: la máquina que deduplica y asigna `seq` (SPEC-004 R2)
        // es la de PairingViewModel; aquí no hay segunda GamepadStateMachine.
        onInput(physicalGamepad.fusedWith(gamepadSurface.snapshot()))
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
