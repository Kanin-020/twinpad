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
 * CR-096: canal Bluetooth HID nativo como único transporte del gamepad. El
 * canal se arranca con la sesión LAN Linked o ante un handshake fallido, y
 * el gamepad sólo sale por BT (SPEC-004 §8.3). La LAN no transporta el gamepad.
 *
 * Los reportes por BT no llevan touch ni inercia (ADR-009 §5: exclusivos
 * de la ruta LAN/SDK).
 */
internal class GamepadBtSwitcher(
    private val isLinked: () -> Boolean,
    private val onBtStateChanged: () -> Unit = {},
) {
    private var btHid: BluetoothHidGamepad? = null
    private var appContext: Context? = null
    private var rumble: RumblePlayer? = null

    @Volatile
    private var btReady = false
    private var wasLinked = false

    /** Arranca el canal BT HID al abrir la app (prueba de banco: el teléfono
     * se anuncia como gamepad Bluetooth con la app abierta, sin LAN). */
    fun startChannel(context: Context) {
        if (btHid != null) return
        appContext = context.applicationContext
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

    /** Ruta de transporte del gamepad: LAN si está Linked; si no, BT si está listo. */
    fun route(): InputTransport = HandoverPolicy.route(lanLinked = isLinked(), btReady = btReady)

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

    /** CR-059: el permiso llegó en caliente; reinicia el armado si la LAN
     * sigue caída (el diálogo lo lanza la Activity al observar la demanda). */
    fun onPermissionGranted() {
        Log.i(TAG, "bt-hid: permiso concedido; rearmando el canal BT")
        val context = appContext ?: return
        btHid?.stop()
        btHid = null
        startChannel(context)
    }

    /** Libera el perfil si quedó armado (solo al destruir el ViewModel). */
    fun shutdown() {
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
