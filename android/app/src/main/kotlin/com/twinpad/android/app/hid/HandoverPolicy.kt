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
    /** Ruta del transporte del gamepad (CR-111): canal BT HID si está operativo
     * (por defecto); si no, LAN si la sesión está Linked; sin ninguna, H0.
     * CR-128: con un receptor que no consume el HID del sistema —el Plugin
     * Cemu lee los inputs por LAN— la ruta LAN manda mientras haya sesión y
     * el BT queda como respaldo sin LAN.
     * CR-130: con el mando virtual del Servidor activo el perfil HID local
     * está suspendido: la única ruta es LAN con sesión; sin sesión, H0. */
    fun route(
        lanLinked: Boolean,
        btReady: Boolean,
        preferLan: Boolean = false,
        virtualPadActive: Boolean = false,
    ): InputTransport =
        when {
            virtualPadActive && lanLinked -> InputTransport.LAN
            virtualPadActive -> InputTransport.NONE
            preferLan && lanLinked -> InputTransport.LAN
            btReady -> InputTransport.BT
            lanLinked -> InputTransport.LAN
            else -> InputTransport.NONE
        }
}
