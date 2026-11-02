package com.twinpad.android.app.pairing

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.hid.GamepadState
import com.twinpad.android.app.hid.GamepadStateMachine
import com.twinpad.android.app.hid.HandoverPolicy
import com.twinpad.android.app.hid.InputTransport
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.hid.BluetoothHidGamepad
import com.twinpad.android.hid.SignatureHidCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CR-059/ADR-009: conmutador del gamepad entre la ruta LAN (sesión de
 * SPEC-001) y el canal Bluetooth HID nativo. Toda la cronología del
 * failover vive aquí: arranque al perder la LAN o ante un handshake fallido
 * (sin Wi-Fi: alternativa completa), regreso con histéresis de 5 s y
 * decisión de ruta por [HandoverPolicy] (SPEC-004 §8.3).
 *
 * Los reportes por BT no llevan touch ni inercia (ADR-009 §5: exclusivos
 * de la ruta LAN/SDK).
 */
internal class GamepadBtSwitcher(
    private val app: Application,
    private val gamepad: GamepadStateMachine,
    private val scope: CoroutineScope,
    private val isLinked: () -> Boolean,
    private val requestPermission: () -> Unit,
) {
    private var btHid: BluetoothHidGamepad? = null

    @Volatile
    private var btReady = false
    private var btReleaseJob: Job? = null
    private var wasLinked = false

    private val listener =
        object : BluetoothHidGamepad.Listener {
            override fun onChannelReady() {
                btReady = true
                Log.i(TAG, "bt-hid: canal BT listo (failover/alternativa activo)")
                // Con la LAN caída y el BT operativo: H0→H1 con salida BT.
                if (isLinked().not() && gamepad.state == GamepadState.OFFLINE) {
                    gamepad.onChannelReady()?.let { sendOverBt(it) }
                }
            }

            override fun onChannelLost() {
                btReady = false
                Log.i(TAG, "bt-hid: canal BT perdido")
                if (isLinked().not()) {
                    gamepad.onChannelDown() // §6 H1→H0
                }
            }
        }

    /**
     * Procesa la transición de estado de la Pantalla de Pairing: decide si
     * el canal BT arranca (caída de sesión / handshake fallido) o si
     * corresponde programar su liberación por histéresis (regreso a LAN).
     */
    fun onUiState(
        previous: PairingUiState,
        current: PairingUiState,
    ) {
        val linked = current is PairingUiState.Linked
        if (linked) {
            scheduleRelease()
        } else if (triggeredFailover(previous, current)) {
            maybeStart()
        }
        wasLinked = linked
    }

    /** Ruta de transporte del gamepad para el estado de la sesión. */
    fun route(linked: Boolean): InputTransport = HandoverPolicy.route(linked, btReady)

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
        Log.i(TAG, "bt-hid: permiso concedido en caliente; reintentando armado")
        if (!isLinked() && !btReady) maybeStart()
    }

    /** Libera el perfil si quedó armado (solo al destruir el ViewModel). */
    fun shutdown() {
        btReleaseJob?.cancel()
        btHid?.stop()
        btHid = null
        btReady = false
        wasLinked = false
    }

    private fun triggeredFailover(
        previous: PairingUiState,
        current: PairingUiState,
    ): Boolean {
        if (wasLinked && current !is PairingUiState.Linked) {
            Log.i(TAG, "bt-hid: sesión LAN caída; intentando canal BT")
            return true
        }
        // Sin Wi-Fi (alternativa completa): el handshake terminó en Failure.
        return previous is PairingUiState.Handshaking && current is PairingUiState.Failure
    }

    /** Arranca el canal BT una sola vez; sin permiso lo demanda en caliente. */
    private fun maybeStart() {
        if (btReady) return
        val context = app.applicationContext
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "bt-hid: sin BLUETOOTH_CONNECT; solicitando permiso al usuario")
            requestPermission()
            return
        }
        val hid = btHid ?: BluetoothHidGamepad(context).also { btHid = it }
        if (hid.currentChannel() == BluetoothHidGamepad.Channel.CONNECTED) {
            btReady = true // canal vivo de una conmutación anterior
            return
        }
        hid.start(listener)
    }

    /** Histéresis: libera el perfil tras 5 s si la LAN sigue sana. */
    private fun scheduleRelease() {
        if (!btReady || btHid == null) return
        btReleaseJob?.cancel()
        btReleaseJob =
            scope.launch {
                delay(HandoverPolicy.BT_RELEASE_HYSTERESIS_MS)
                if (isLinked()) {
                    Log.i(TAG, "bt-hid: LAN estable; liberando perfil BT (histéresis)")
                    btHid?.stop()
                    btHid = null
                    btReady = false
                }
            }
    }

    private companion object {
        private const val TAG = "TwinpadBt"
    }
}
