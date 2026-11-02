package com.twinpad.android.app.pairing

/** Estado de selección de Plugin durante una sesión de Pairing (CR-037):
 * qué origen de A/V pidió el usuario y cuál eligió el server en automático. */
internal class PairingSelectionState {
    /** Hay una espera en curso por respuesta del server (R9). */
    var pending = false

    /** gameId de un `plugin_select` explícito del usuario, si lo hubo. */
    var requestedGameId: String? = null

    /** gameId elegido por el server cuando no hubo selección explícita. */
    var automaticGameId: String? = null

    /** Reset del estado al terminar la sesión o cambiar de quién pide. */
    fun clear() {
        pending = false
        requestedGameId = null
        automaticGameId = null
    }
}
