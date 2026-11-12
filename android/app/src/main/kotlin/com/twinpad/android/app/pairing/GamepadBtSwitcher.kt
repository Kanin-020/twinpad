package com.twinpad.android.app.pairing

import android.util.Log
import com.twinpad.android.app.core.PairingUiState
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
) {
    private var btHid: BluetoothHidGamepad? = null

    @Volatile
    private var btReady = false
    private var wasLinked = false

    /** Procesa la transición de estado de la Pantalla de Pairing: arranca el
     * canal BT con la sesión Linked o ante un handshake fallido (CR-096). */
    fun onUiState(current: PairingUiState) {
        // Gamepad solo por LAN: el canal BT HID no se arranca.
        wasLinked = current is PairingUiState.Linked
    }

    /** Ruta de transporte del gamepad: solo LAN (sin BT). */
    fun route(): InputTransport = HandoverPolicy.route(lanLinked = isLinked(), btReady = false)

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
            )
        btHid?.sendReport(encoded)
    }

    /** CR-059: el permiso llegó en caliente; reinicia el armado si la LAN
     * sigue caída (el diálogo lo lanza la Activity al observar la demanda). */
    fun onPermissionGranted() {
        Log.i(TAG, "bt-hid: permiso concedido; el gamepad va solo por LAN")
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
    }
}
