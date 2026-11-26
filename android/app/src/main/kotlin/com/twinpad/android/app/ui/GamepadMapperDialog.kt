package com.twinpad.android.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.twinpad.android.app.R
import com.twinpad.android.app.hid.ChordCapture
import com.twinpad.android.app.hid.GamepadMapping
import com.twinpad.android.app.hid.GamepadProfile
import com.twinpad.android.app.hid.GamepadProfileCodec
import com.twinpad.android.app.hid.GamepadProfileSet
import com.twinpad.android.app.hid.MappedControl
import com.twinpad.android.app.hid.PhysicalAxes
import com.twinpad.android.app.hid.PhysicalInput
import com.twinpad.android.app.hid.captureAxis
import com.twinpad.android.app.hid.captureKey
import com.twinpad.android.app.hid.captureKeys

/**
 * SPEC-011: pantalla de reconocimiento y ajuste del mando físico. Lista las
 * entradas canónicas con su mapeo vigente, captura un botón o un eje al tocar
 * una fila y guarda el cambio al instante en el perfil activo (o en un perfil
 * nuevo si estaba en «Predeterminado»).
 *
 * Estética: DESIGN.md (Featurebase) — Charcoal Panel flotante, filas Ink
 * Surface con borde Slate Edge, acciones blancas/outlined y Signal Violet solo
 * en los estados seleccionados o en captura.
 */
internal class GamepadMapperDialog(
    private val activity: Activity,
    private val store: GamepadProfileStore,
    private val onMappingChanged: (GamepadMapping) -> Unit,
) {
    var isShowing = false
        private set

    private val metrics get() = activity.resources.displayMetrics

    private var dialog: Dialog? = null
    private var device: InputDevice? = null
    private var modelKey = ""
    private var profiles: GamepadProfileSet? = null
    private var mapping: GamepadMapping = GamepadMapping.DEFAULT
    private var capturing: MappedControl? = null

    /** SPEC-011 §7 paso 4 (CR-123): combina las teclas pulsadas a la vez. */
    private val chord = ChordCapture()

    /** CR-124: la captura caduca si no llega ninguna entrada. */
    private val timer =
        CaptureCountdownTicker(
            onSecond = { renderStatus() },
            onExpired = {
                stopCapture()
                render()
            },
        )
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var profileButton: MaterialButton
    private lateinit var cancelCapture: MaterialButton
    private lateinit var title: TextView

    /** `true` mientras el mapeador muestra o captura: la Activity le entrega
     * los eventos del mando en vez de la sesión. */
    fun consumesEvents(): Boolean = isShowing && capturing != null

    fun show(device: InputDevice) {
        this.device = device
        modelKey = gamepadModelKey(device)
        profiles = store.load(modelKey)
        if (profiles == null && store.hasCorrupt(modelKey)) {
            Toast.makeText(activity, R.string.gamepad_mapper_corrupt, Toast.LENGTH_LONG).show()
        }
        mapping = profiles?.activeProfile()?.mapping ?: GamepadMapping.DEFAULT
        onMappingChanged(mapping)
        if (dialog == null) dialog = buildDialog()
        isShowing = true
        capturing = null
        render()
        dialog?.show()
        dialog?.let { styleWindow(it, dialogWidth(), dialogHeight(MAIN_HEIGHT_FRACTION, MAIN_MAX_HEIGHT)) }
    }

    fun dismiss() {
        stopCapture()
        isShowing = false
        dialog?.dismiss()
    }

    fun onDeviceRemoved(removedId: Int) {
        if (device?.id == removedId && isShowing) {
            dismiss()
        }
    }

    /** Sale de la captura sin cambiar el mapeo (cancelar, caducidad o cierre). */
    private fun stopCapture() {
        capturing = null
        chord.reset()
        timer.stop()
    }

    /** Entra en captura (o sale con `null`) con su cuenta atrás (CR-124). */
    private fun setCapture(control: MappedControl?) {
        if (control == null) {
            stopCapture()
        } else {
            capturing = control
            chord.reset()
            timer.restart()
        }
        render()
    }

    /** Captura de botones (SPEC-011 §7 paso 4, CR-123): acumula las teclas
     * pulsadas a la vez y asigna al soltarlas todas; una pulsación suelta
     * sigue dando un botón y dos simultáneas dan una combinación. */
    fun onKey(event: KeyEvent): Boolean {
        if (!consumesEvents()) return false
        val control = capturing ?: return true
        timer.restart()
        val combination =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) chord.press(event.keyCode)
                    null
                }
                KeyEvent.ACTION_UP -> chord.release(event.keyCode)
                else -> null
            }
        if (combination != null) {
            val input =
                if (control.button) {
                    captureKeys(combination)
                } else {
                    // Gatillos y cruceta siguen con un único botón (o un eje).
                    combination.singleOrNull()?.let(::captureKey)
                }
            input?.let(::assign)
        }
        return true
    }

    /** Captura de un eje: el que más se movió sobre el umbral. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!consumesEvents()) return false
        timer.restart()
        val input = captureAxis(motionAxes(event))
        if (input != null) assign(input)
        return true
    }

    /** El mapeador vive en su propia ventana: los `KeyEvent`/`MotionEvent`
     * del mando llegan a esta ventana y no a `Activity.dispatchKeyEvent`
     * (la Activity conserva el foco del resto de la pantalla), así que aquí
     * se leen antes del despacho normal (SPEC-011 §7). */
    private inner class MapperDialog(
        context: Context,
    ) : Dialog(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (onKey(event)) return true
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (onMotion(event)) return true
            return super.dispatchGenericMotionEvent(event)
        }
    }

    private fun buildDialog(): Dialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_gamepad_mapper, null)
        title = view.findViewById(R.id.mapper_title)
        status = view.findViewById(R.id.mapper_status)
        profileButton = view.findViewById(R.id.mapper_profile)
        cancelCapture = view.findViewById(R.id.mapper_cancel_capture)
        rows = view.findViewById(R.id.mapper_rows)
        profileButton.setOnClickListener { chooseProfile() }
        cancelCapture.setOnClickListener {
            stopCapture()
            render()
        }
        view.findViewById<MaterialButton>(R.id.mapper_new_profile).setOnClickListener { createProfile() }
        view.findViewById<MaterialButton>(R.id.mapper_close).setOnClickListener { dismiss() }
        return MapperDialog(activity).apply {
            setContentView(view)
            setOnDismissListener {
                this@GamepadMapperDialog.isShowing = false
                this@GamepadMapperDialog.stopCapture()
            }
        }
    }

    private fun render() {
        val current = device ?: return
        title.text = gamepadDisplayName(current)
        profileButton.text =
            activity.getString(
                R.string.gamepad_mapper_profile,
                profiles?.active ?: activity.getString(R.string.gamepad_mapper_default),
            )
        profileButton.isEnabled = !(profiles?.profiles.isNullOrEmpty())
        renderStatus()
        rows.removeAllViews()
        if (dialogWidth() >= dp(TWO_COLUMN_MIN_WIDTH)) {
            // En horizontal las 19 equivalencias caben en dos columnas y el
            // scroll se reduce a la mitad de filas.
            rows.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            rows.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rows.addView(
                right,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(TWO_COLUMN_GAP)
                },
            )
            MappedControl.entries.forEachIndexed { index, control ->
                val column = if (index % 2 == 0) left else right
                column.addView(controlRow(control, column))
            }
        } else {
            rows.orientation = LinearLayout.VERTICAL
            for (control in MappedControl.entries) {
                rows.addView(controlRow(control, rows))
            }
        }
    }

    /** Texto del estado con la cuenta atrás de la captura (CR-124). */
    private fun renderStatus() {
        val active = capturing
        status.text =
            active?.let {
                activity.getString(R.string.gamepad_mapper_capturing_seconds, it.label, timer.remaining)
            } ?: activity.getString(R.string.gamepad_mapper_hint)
        val statusColor =
            if (active == null) R.color.design_misted_slate else R.color.design_signal_violet
        status.setTextColor(activity.designColor(statusColor))
        status.setBackgroundResource(
            if (active == null) R.drawable.bg_gamepad_status_idle else R.drawable.bg_gamepad_status_active,
        )
        cancelCapture.visibility = if (active == null) View.GONE else View.VISIBLE
    }

    private fun controlRow(
        control: MappedControl,
        parent: LinearLayout,
    ): View {
        val inputs = mapping.inputsOf(control)
        val selected = control == capturing
        val row = LayoutInflater.from(activity).inflate(R.layout.item_gamepad_mapping_row, parent, false)
        row.findViewById<TextView>(R.id.row_label).text = control.label
        val value = row.findViewById<TextView>(R.id.row_value)
        value.text =
            if (inputs.isEmpty()) {
                activity.getString(R.string.gamepad_mapper_unassigned)
            } else {
                inputs.joinToString(" · ") { GamepadMapperLabels.describe(activity, it) }
            }
        value.setTextColor(
            activity.designColor(
                when {
                    selected -> R.color.design_signal_violet
                    inputs.isEmpty() -> R.color.design_quiet_slate
                    else -> R.color.design_misted_slate
                },
            ),
        )
        row.findViewById<View>(R.id.row_dot).backgroundTintList =
            ColorStateList.valueOf(
                activity.designColor(if (selected) R.color.design_signal_violet else R.color.design_slate_edge),
            )
        row.setBackgroundResource(if (selected) R.drawable.bg_gamepad_row_active else R.drawable.bg_gamepad_row)
        row.isSelected = selected
        row.setOnClickListener {
            setCapture(if (capturing == control) null else control)
        }
        return row
    }

    private fun assign(input: PhysicalInput) {
        val control = capturing ?: return
        // SPEC-011 §8: los sticks solo usan ejes; los botones, solo teclas.
        val accepted =
            when (input) {
                is PhysicalInput.Key -> !control.stickAxis
                is PhysicalInput.Combo -> control.button
                is PhysicalInput.Axis -> !control.button
            }
        if (!accepted) return // E1: la fila sigue en captura
        // Los sticks conservan la dirección física del eje; el sentido no se
        // invierte al asignar una fila de stick.
        val effective = if (control.stickAxis && input is PhysicalInput.Axis) input.copy(sign = 1) else input
        val current = profiles
        val activeProfile = current?.activeProfile()
        val base = activeProfile?.mapping ?: mapping
        val updated = base.with(control, effective)
        val name =
            current?.active
                ?: GamepadProfileCodec.nextProfileName(current?.profiles.orEmpty().map { it.name })
        val nextProfiles =
            if (activeProfile == null) {
                current?.profiles.orEmpty() + GamepadProfile(name, updated)
            } else {
                current!!.profiles.map { profile ->
                    if (profile.name == name) profile.copy(mapping = updated) else profile
                }
            }
        profiles = GamepadProfileSet(modelKey, name, nextProfiles)
        profiles?.let { store.save(it) }
        mapping = updated
        onMappingChanged(updated)
        stopCapture()
        render()
    }

    private fun chooseProfile() {
        val current = profiles ?: return
        val names =
            listOf(activity.getString(R.string.gamepad_mapper_default)) + current.profiles.map { it.name }
        val selected =
            if (current.active == null) 0 else current.profiles.indexOfFirst { it.name == current.active } + 1
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_gamepad_profile, null)
        val options = view.findViewById<LinearLayout>(R.id.profile_options)
        val dialog = Dialog(activity)
        names.forEachIndexed { index, name ->
            val row = LayoutInflater.from(activity).inflate(R.layout.item_gamepad_profile_row, options, false)
            row.findViewById<TextView>(R.id.profile_option_label).text = name
            row.findViewById<View>(R.id.profile_option_check).visibility =
                if (index == selected) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                selectProfile(if (index == 0) null else current.profiles.getOrNull(index - 1))
                dialog.dismiss()
                render()
            }
            options.addView(row)
        }
        view.findViewById<MaterialButton>(R.id.profile_close).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
        styleWindow(dialog, dialogWidth(), dialogHeight(PROFILE_HEIGHT_FRACTION, PROFILE_MAX_HEIGHT))
    }

    private fun selectProfile(profile: GamepadProfile?) {
        profiles = profiles?.copy(active = profile?.name)
        profiles?.let { store.save(it) }
        mapping = profile?.mapping ?: GamepadMapping.DEFAULT
        onMappingChanged(mapping)
        stopCapture()
    }

    private fun createProfile() {
        val current = profiles
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_gamepad_new_profile, null)
        val input = view.findViewById<EditText>(R.id.profile_name_input)
        val error = view.findViewById<TextView>(R.id.profile_name_error)
        val dialog = Dialog(activity)
        val create = view.findViewById<MaterialButton>(R.id.profile_create)
        create.setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            val message =
                when {
                    name.isEmpty() -> R.string.gamepad_mapper_name_required
                    current?.profiles.orEmpty().any { it.name == name } -> R.string.gamepad_mapper_name_in_use
                    else -> null
                }
            if (message != null) {
                // E3: no se crea; el diálogo permanece y explica el motivo.
                error.setText(message)
                error.visibility = View.VISIBLE
            } else {
                val set =
                    GamepadProfileSet(
                        modelKey = modelKey,
                        active = name,
                        profiles = current?.profiles.orEmpty() + GamepadProfile(name, mapping),
                    )
                store.save(set)
                profiles = set
                stopCapture()
                dialog.dismiss()
                render()
            }
        }
        view.findViewById<MaterialButton>(R.id.profile_cancel).setOnClickListener { dialog.dismiss() }
        input.setOnEditorActionListener { _, _, _ ->
            create.performClick()
            true
        }
        dialog.setContentView(view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        styleWindow(dialog, dialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
    }

    private fun motionAxes(event: MotionEvent): Map<Int, Float> =
        PhysicalAxes.tracked.associateWith { axis ->
            // Fuera de rango + historial: el mapper solo mira valores finitos.
            event.getAxisValue(axis).takeIf { it.isFinite() } ?: 0f
        }

    private fun styleWindow(
        dialog: Dialog,
        width: Int,
        height: Int,
    ) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val params = window.attributes
        params.dimAmount = DIM_AMOUNT
        window.attributes = params
        window.setLayout(width, height)
    }

    private fun dialogWidth(): Int {
        return dp(MAX_WIDTH).coerceAtMost(metrics.widthPixels - dp(SCREEN_MARGIN))
    }

    private fun dialogHeight(
        fraction: Float,
        maxDp: Int,
    ): Int {
        return (metrics.heightPixels * fraction).toInt().coerceAtMost(dp(maxDp))
    }

    private fun dp(value: Int): Int = (value * metrics.density).toInt()

    private companion object {
        const val DIM_AMOUNT = 0.72f
        const val MAX_WIDTH = 680
        const val SCREEN_MARGIN = 32
        const val TWO_COLUMN_MIN_WIDTH = 560
        const val TWO_COLUMN_GAP = 8
        const val MAIN_HEIGHT_FRACTION = 0.94f
        const val MAIN_MAX_HEIGHT = 760
        const val PROFILE_HEIGHT_FRACTION = 0.68f
        const val PROFILE_MAX_HEIGHT = 560
    }
}

private fun Activity.designColor(resId: Int): Int = ContextCompat.getColor(this, resId)
