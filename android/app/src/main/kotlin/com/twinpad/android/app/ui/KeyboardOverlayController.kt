package com.twinpad.android.app.ui

import android.app.Activity
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.twinpad.android.app.R
import com.twinpad.android.app.wire.KeyboardAction
import com.twinpad.android.app.wire.KeyboardInputCodec
import com.twinpad.android.app.wire.KeyboardRequestMessage

/**
 * CR-044: overlay de teclado de la sesión; reemplaza al teclado en pantalla de
 * Cemu mientras Twinpad esté conectado (el HLE `swkbd` no dibuja su overlay y
 * publica `keyboard_request` con el texto vigente y el techo de caracteres).
 *
 * El IME del teléfono vive bajo demanda: solo con el juego «cemu»
 * seleccionado y `keyboard_request on=true` se muestra. Cada cambio del IME
 * envía un `keyboard_input update` con el texto completo en base64url; el
 * botón del teclado (acción Done) y «Aceptar» envían `confirm`; «Cancelar»
 * envía `cancel`. El desaparecer gobernado por Cemu (`on=false`) solo
 * oculta el overlay (ya conoce el cierre: no se replica hacia el wire).
 */
class KeyboardOverlayController(
    activity: Activity,
    private val onLine: (String) -> Unit,
) {
    private val overlay = activity.findViewById<android.view.View>(R.id.keyboard_overlay)
    private val input = activity.findViewById<EditText>(R.id.keyboard_input)
    private val accept = activity.findViewById<android.widget.Button>(R.id.btn_keyboard_accept)
    private val cancel = activity.findViewById<android.widget.Button>(R.id.btn_keyboard_cancel)
    private val inputManager =
        activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager

    /** True mientras el overlay está visible (los watchers miran esto). */
    private var openState = false

    /** True mientras se aplica texto remoto: evita eco de la sync al wire. */
    private var applyingRemote = false

    init {
        input.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) {
                    if (openState && !applyingRemote) send(KeyboardAction.Update, s?.toString().orEmpty())
                }
            },
        )
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                accept() // acción Done del IME: equivalente al OK del juego
                true
            } else {
                false
            }
        }
        accept.setOnClickListener { accept() }
        cancel.setOnClickListener { dismiss() }
        // El overlay muere con la actividad; no hay nada que liberar.
    }

    /** Aplica la demanda vigente combinada (juego «cemu» + `keyboard_request`). */
    fun apply(
        request: KeyboardRequestMessage?,
        gameSelected: Boolean,
    ) {
        val demand = request?.takeIf { it.on && gameSelected }
        if (demand == null) {
            // Cierre gobernado por Cemu (`on=false` o demanda nula): sin eco.
            close(notifyGame = false)
        } else {
            open(demand)
        }
    }

    /** Cierre por el usuario (botón «Cancelar»): informa de `cancel` a Cemu. */
    fun dismiss() = close(notifyGame = true)

    private fun open(demand: KeyboardRequestMessage) {
        if (!openState) {
            overlay.visibility = android.view.View.VISIBLE
            openState = true
        }
        // Aplica texto/techo solo si difieren: el eco de Cemu tras aplicar
        // cada update no debe reiniciar cursor ni composición del IME.
        applyingRemote = true
        if (input.text?.toString() != demand.text) {
            input.setText(demand.text)
            input.setSelection(input.text?.length ?: 0)
        }
        applyingRemote = false
        input.filters = arrayOf(InputFilter.LengthFilter(demand.maxLength))
        input.requestFocus()
        inputManager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun close(notifyGame: Boolean) {
        if (!openState) return
        openState = false
        inputManager.hideSoftInputFromWindow(input.windowToken, 0)
        overlay.visibility = android.view.View.GONE
        if (notifyGame) send(KeyboardAction.Cancel, "")
    }

    private fun accept() {
        if (!openState) return
        // El HLE «siempre acepta la entrada» (swkbd_finishInput): el juego
        // cierra el form y el `off` de vuelta llega por su disappear.
        send(KeyboardAction.Confirm, "")
    }

    private fun send(
        action: KeyboardAction,
        text: String,
    ) {
        onLine(KeyboardInputCodec.encode(action, text))
    }
}
