package com.twinpad.android.app.hid

/**
 * SPEC-011 §7 paso 4 (CR-123): acumula las teclas que están pulsadas a la vez
 * durante la captura de una fila y devuelve la combinación más amplia
 * observada cuando se sueltan todas. Una pulsación aislada devuelve una sola
 * tecla, así que el caso de un botón suelto no cambia.
 *
 * Sin dependencias de Android (ENG §5 P11): la ventana del mapeador solo le
 * pasa los `keyCode` con su acción.
 */
class ChordCapture {
    private val held = linkedSetOf<Int>()
    private var widest: List<Int> = emptyList()

    /** Tecla pulsada: entra en la combinación vigente. */
    fun press(keyCode: Int) {
        held.add(keyCode)
        if (held.size > widest.size) widest = held.toList()
    }

    /** Tecla soltada: `null` mientras quede alguna pulsada; si era la última,
     * la combinación a asignar (vacía si no se pulsó nada desde el último
     * reinicio). */
    fun release(keyCode: Int): List<Int>? {
        held.remove(keyCode)
        if (held.isNotEmpty()) return null
        val combination = widest
        widest = emptyList()
        return combination
    }

    /** Descarta lo acumulado (cambio de fila, cancelar o cerrar el mapeador). */
    fun reset() {
        held.clear()
        widest = emptyList()
    }
}
