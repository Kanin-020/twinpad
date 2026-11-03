package com.twinpad.android.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.hid.PhysicalGamepadAlgo
import com.twinpad.android.app.hid.PhysicalMotion
import com.twinpad.android.app.surface.ControlProfile
import com.twinpad.android.app.surface.ControlProfileCodec
import com.twinpad.android.app.surface.ControlProfileStore
import com.twinpad.android.app.surface.ProfileException
import com.twinpad.android.app.surface.ProfileMapLayout
import com.twinpad.android.app.surface.applyFaceDistribution
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.MotionInput
import kotlinx.coroutines.launch

/** Touch surface that maps profile gestures to the Twinpad gamepad signature. */
class ProfileSurface(
    context: Context,
    private val onInput: (InputReport) -> Unit,
) : View(context) {
    private val gamepadSurface = GamepadSurfaceState()

    /** Mando físico conectado al teléfono (CR-028): su estado se fusiona
     * con la superficie táctil al componer cada reporte (SPEC-004). */
    private val physicalGamepad = PhysicalGamepadAlgo()
    private val store = ControlProfileStore(context)

    /** Contexto para inflar el layout de mapeo del desarrollador (CR-067). */
    private val layoutContext: Context = context.applicationContext
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
            touchController.controlsVisible = value
            invalidate()
        }
    private var motionSample: MotionInput? = null
    private var activeGameId = ""
    private var profileLoadGeneration = 0

    var profile: ControlProfile = ControlProfileCodec.defaultProfile()
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
            // CR-067: cadena de perfil — JSON por juego de `filesDir/profiles/`
            // (Config externa, SPEC-007) o el layout de mapeo del desarrollador
            // con drag & drop (`res/layout/profile_map.xml`) o default canónico.
            val fromStore = if (gameId.isEmpty()) null else store.load(gameId)
            val loaded =
                fromStore
                    ?: try {
                        ProfileMapLayout.extract(LayoutInflater.from(layoutContext), width, height)
                    } catch (_: ProfileException) {
                        // SPEC-008 §9: un layout de mapeo inválido se descarta
                        // (sin derribar la superficie) y cae al default.
                        null
                    }
            if (generation != profileLoadGeneration) return@launch
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = touchController.onTouch(event, this, profile)
        if (handled) invalidate()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            DrawArgs(
                canvas = canvas,
                profile = profile,
                state = touchController.renderState(),
                width = width.toFloat(),
                height = height.toFloat(),
                showControls = showControls,
            ),
        )
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
}
