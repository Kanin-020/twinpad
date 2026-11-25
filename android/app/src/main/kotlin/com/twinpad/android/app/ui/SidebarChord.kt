package com.twinpad.android.app.ui

import android.view.KeyEvent
import com.twinpad.android.app.hid.PhysicalGamepadAlgo.KeyCodes

/**
 * Combinación L1 + R1 + START del mando físico que abre el drawer de ajustes
 * durante la sesión. Es un comportamiento por defecto de la app, no una
 * preferencia; las teclas no se consumen y siguen llegando al juego.
 */
internal class SidebarChord(private val onTriggered: () -> Unit) {
    private val held = mutableSetOf<Int>()

    fun onKey(event: KeyEvent) {
        val code = event.keyCode
        if (code !in KEYS) return
        when (event.action) {
            KeyEvent.ACTION_UP -> held.remove(code)
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return
                held.add(code)
                if (held.containsAll(KEYS)) onTriggered()
            }
        }
    }

    private companion object {
        val KEYS = setOf(KeyCodes.BUTTON_L1, KeyCodes.BUTTON_R1, KeyCodes.BUTTON_START)
    }
}
