package com.twinpad.android.app.hid

/**
 * Transporte del gamepad (CR-059/ADR-009): ruta LAN de la sesión (SPEC-001)
 * o canal Bluetooth HID nativo; NONE es H0 (los controles no envían, §6).
 */
enum class InputTransport {
    /** Sesión LAN Linked: los reportes viajan por la conexión del server. */
    LAN,

    /** Canal BT operativo y LAN caída (o handshake fallido): salida HID. */
    BT,

    /** H0: sin canal operativo, los controles no envían. */
    NONE,
}

/**
 * Política de handover del gamepad (CR-059/ADR-009): la decisión de ruta
 * LAN ↔ BT vive aquí, en una función pura. La cronología (arranque del
 * canal BT, liberación con histéresis) la gobierna el conmutador de la app.
 */
object HandoverPolicy {
    /** Histéresis (ms): al volver la LAN, se libera el canal BT solo si la sesión
     * sigue Linked pasados 5 s (evita reinicios del perfil en cortes breves). */
    const val BT_RELEASE_HYSTERESIS_MS: Long = 5_000L

    /** Ruta del transporte del gamepad: la LAN tiene prioridad; si cayó y el
     * canal BT está operativo, hay failover a Bluetooth; sin ninguno, H0. */
    fun route(
        lanLinked: Boolean,
        btReady: Boolean,
    ): InputTransport =
        when {
            lanLinked -> InputTransport.LAN
            btReady -> InputTransport.BT
            else -> InputTransport.NONE
        }
}
