package com.twinpad.android.app.pairing

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.twinpad.android.app.hid.HandoverPolicy
import com.twinpad.android.app.hid.InputTransport
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.hid.BluetoothHidGamepad
import com.twinpad.android.hid.SignatureHidCodec

/**
 * CR-096: canal Bluetooth HID nativo del gamepad. El canal se arranca al
 * abrir la app y la ruta se decide por [HandoverPolicy] (SPEC-004 §8.3): BT
 * por defecto, LAN con un receptor que la prefiera (CR-128) o mientras el
 * Servidor tenga activo su mando virtual (CR-130). En ese modo el perfil HID
 * local se suspende para que el PC sólo enumere el DualSense del Servidor.
 *
 * Los reportes por BT no llevan touch ni inercia (ADR-009 §5: exclusivos
 * de la ruta LAN/SDK).
 */
internal class GamepadBtSwitcher(
    private val isLinked: () -> Boolean,
    private val onBtStateChanged: () -> Unit = {},
    /** CR-128: receptor que sólo lee el `input_report` de LAN (Plugin Cemu). */
    private val preferLan: () -> Boolean = { false },
) {
    @Volatile
    private var btHid: BluetoothHidGamepad? = null

    @Volatile
    private var appContext: Context? = null
    private var rumble: RumblePlayer? = null

    @Volatile
    private var btReady = false
    private var wasLinked = false

    /** CR-130: el Servidor tiene creado su HID DualSense virtual. */
    @Volatile
    private var virtualPadActive = false

    /** Arranca el canal BT HID al abrir la app (prueba de banco: el teléfono
     * se anuncia como gamepad Bluetooth con la app abierta, sin LAN). */
    fun startChannel(context: Context) {
        appContext = context.applicationContext
        if (btHid != null || virtualPadActive) return
        rumble = RumblePlayer(context)
        val hid = BluetoothHidGamepad(context)
        btHid = hid
        hid.start(
            object : BluetoothHidGamepad.Listener {
                override fun onChannelReady() {
                    btReady = true
                    Log.i(TAG, "bt-hid: canal listo")
                    onBtStateChanged()
                }

                override fun onChannelLost() {
                    btReady = false
                    Log.i(TAG, "bt-hid: canal perdido")
                    onBtStateChanged()
                }

                override fun onRumble(
                    strong: Int,
                    weak: Int,
                ) {
                    rumble?.playStrength(strong, weak)
                }
            },
        )
    }

    fun isBtReady(): Boolean = btReady

    /**
     * CR-130: suspende el perfil HID local mientras el DualSense virtual del
     * Servidor está activo y lo rearma al apagarse. Así Steam sólo enumera el
     * mando del Servidor y todos los inputs viajan por la sesión LAN.
     */
    fun setVirtualPadActive(active: Boolean) {
        if (virtualPadActive == active) return
        virtualPadActive = active
        val context = appContext
        if (active) {
            val hid = btHid
            btHid = null
            btReady = false
            hid?.stop()
            onBtStateChanged()
        } else if (context != null) {
            startChannel(context)
            onBtStateChanged()
        }
    }

    /** Ruta de transporte del gamepad: LAN mientras el mando virtual del
     * Servidor esté activo; si no, BT si está listo, o LAN cuando el receptor
     * la exija (CR-128) o el BT no esté operativo. */
    fun route(): InputTransport =
        HandoverPolicy.route(
            lanLinked = isLinked(),
            btReady = btReady,
            preferLan = preferLan(),
            virtualPadActive = virtualPadActive,
        )

    /** Envía el reporte por la ruta BT, si el canal está vivo. */
    fun sendOverBt(report: InputReport) {
        val encoded =
            SignatureHidCodec.encode(
                report.stickLx,
                report.stickLy,
                report.stickRx,
                report.stickRy,
                report.dpad,
                report.buttons,
                report.triggerL2,
                report.triggerR2,
                batteryPercent = readBatteryPercent(),
            )
        btHid?.sendReport(encoded)
    }

    /** CR-110: porcentaje de batería del teléfono (0 si el dispositivo no lo informa). */
    private fun readBatteryPercent(): Int {
        val manager = appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 0
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, MAX_BATTERY_PERCENT)
    }

    /** CR-059: el permiso llegó en caliente; rearma el canal si no está armado.
     * CR-122: la Activity pide BLUETOOTH_CONNECT al abrir y el resultado llega
     * concedido aunque ya lo estuviera: recrear el canal HID en cada arranque
     * cerraba el enlace recién abierto y dejaba el registro anterior ocupado
     * (reintentos de 5-20 s), así que el canal existente sólo se reintenta. */
    fun onPermissionGranted() {
        // CR-130: el modo virtual mantiene el perfil HID local suspendido.
        if (virtualPadActive) return
        val hid = btHid
        val context = appContext
        if (hid == null) {
            if (context != null) startChannel(context)
            return
        }
        Log.i(TAG, "bt-hid: permiso concedido; se reintenta el canal existente")
        hid.retryNow()
    }

    /** Libera el perfil si quedó armado (solo al destruir el ViewModel). */
    fun shutdown() {
        virtualPadActive = false
        btHid?.stop()
        btHid = null
        btReady = false
        wasLinked = false
    }

    private companion object {
        private const val TAG = "TwinpadBt"
        private const val MAX_BATTERY_PERCENT = 100
    }
}
