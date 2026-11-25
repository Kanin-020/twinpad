package com.twinpad.android.app.ui

import android.app.Activity
import android.app.Dialog
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
import com.twinpad.android.app.hid.GamepadMapping
import com.twinpad.android.app.hid.GamepadProfile
import com.twinpad.android.app.hid.GamepadProfileCodec
import com.twinpad.android.app.hid.GamepadProfileSet
import com.twinpad.android.app.hid.MappedControl
import com.twinpad.android.app.hid.PhysicalAxes
import com.twinpad.android.app.hid.PhysicalInput
import com.twinpad.android.app.hid.PhysicalKeys
import com.twinpad.android.app.hid.captureAxis
import com.twinpad.android.app.hid.captureKey

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
        capturing = null
        isShowing = false
        dialog?.dismiss()
    }

    fun onDeviceRemoved(removedId: Int) {
        if (device?.id == removedId && isShowing) {
            capturing = null
            dismiss()
        }
    }

    /** Captura de un botón (SPEC-011 §7 paso 4). */
    fun onKey(event: KeyEvent): Boolean {
        if (!consumesEvents()) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        assign(captureKey(event.keyCode))
        return true
    }

    /** Captura de un eje: el que más se movió sobre el umbral. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!consumesEvents()) return false
        val input = captureAxis(motionAxes(event))
        if (input != null) assign(input)
        return true
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
            capturing = null
            render()
        }
        view.findViewById<MaterialButton>(R.id.mapper_new_profile).setOnClickListener { createProfile() }
        view.findViewById<MaterialButton>(R.id.mapper_close).setOnClickListener { dismiss() }
        return Dialog(activity).apply {
            setContentView(view)
            setOnDismissListener {
                this@GamepadMapperDialog.isShowing = false
                this@GamepadMapperDialog.capturing = null
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
        val active = capturing
        status.text =
            active?.let { activity.getString(R.string.gamepad_mapper_capturing, it.label) }
                ?: activity.getString(R.string.gamepad_mapper_hint)
        val statusColor =
            if (active == null) R.color.design_misted_slate else R.color.design_signal_violet
        status.setTextColor(activity.designColor(statusColor))
        status.setBackgroundResource(
            if (active == null) R.drawable.bg_gamepad_status_idle else R.drawable.bg_gamepad_status_active,
        )
        cancelCapture.visibility = if (active == null) View.GONE else View.VISIBLE
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
                inputs.joinToString(" · ", transform = ::describe)
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
            capturing = if (capturing == control) null else control
            render()
        }
        return row
    }

    private fun assign(input: PhysicalInput) {
        val control = capturing ?: return
        // SPEC-011 §8: los sticks solo usan ejes; los botones, solo teclas.
        val accepted =
            when (input) {
                is PhysicalInput.Key -> !control.stickAxis
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
        capturing = null
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
        capturing = null
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
                capturing = null
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

    private fun describe(input: PhysicalInput): String =
        when (input) {
            is PhysicalInput.Key -> keyName(input.keyCode)
            is PhysicalInput.Axis ->
                activity.getString(
                    R.string.gamepad_mapper_axis,
                    axisName(input.axis),
                    if (input.sign > 0) POSITIVE_SIGN else NEGATIVE_SIGN,
                )
        }

    private fun keyName(keyCode: Int): String {
        return KEY_NAMES[keyCode] ?: activity.getString(R.string.gamepad_mapper_key, keyCode)
    }

    private fun axisName(axis: Int): String {
        return AXIS_NAMES[axis] ?: activity.getString(R.string.gamepad_mapper_axis_name, axis)
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
        const val POSITIVE_SIGN = "+"
        const val NEGATIVE_SIGN = "−"

        val KEY_NAMES =
            mapOf(
                PhysicalKeys.BUTTON_A to "A",
                PhysicalKeys.BUTTON_B to "B",
                PhysicalKeys.BUTTON_X to "X",
                PhysicalKeys.BUTTON_Y to "Y",
                PhysicalKeys.BUTTON_L1 to "LB",
                PhysicalKeys.BUTTON_R1 to "RB",
                PhysicalKeys.BUTTON_L2 to "LT",
                PhysicalKeys.BUTTON_R2 to "RT",
                PhysicalKeys.BUTTON_L3 to "L3",
                PhysicalKeys.BUTTON_R3 to "R3",
                PhysicalKeys.BUTTON_START to "Start",
                PhysicalKeys.BUTTON_SELECT to "Select",
                PhysicalKeys.DPAD_UP to "D-pad ↑",
                PhysicalKeys.DPAD_DOWN to "D-pad ↓",
                PhysicalKeys.DPAD_LEFT to "D-pad ←",
                PhysicalKeys.DPAD_RIGHT to "D-pad →",
            )
        val AXIS_NAMES =
            mapOf(
                PhysicalAxes.AXIS_X to "X",
                PhysicalAxes.AXIS_Y to "Y",
                PhysicalAxes.AXIS_Z to "Z",
                PhysicalAxes.AXIS_RX to "RX",
                PhysicalAxes.AXIS_RY to "RY",
                PhysicalAxes.AXIS_RZ to "RZ",
                PhysicalAxes.AXIS_HAT_X to "HAT X",
                PhysicalAxes.AXIS_HAT_Y to "HAT Y",
                PhysicalAxes.AXIS_LTRIGGER to "LT",
                PhysicalAxes.AXIS_RTRIGGER to "RT",
                PhysicalAxes.AXIS_THROTTLE to "THROTTLE",
                PhysicalAxes.AXIS_GAS to "GAS",
                PhysicalAxes.AXIS_BRAKE to "BRAKE",
            )
    }
}

private fun Activity.designColor(resId: Int): Int = ContextCompat.getColor(this, resId)
