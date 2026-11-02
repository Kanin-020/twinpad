package com.twinpad.android.hid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Canal de input del gamepad por Bluetooth HID nativo (CR-059, ADR-009).
 *
 * Registra la app como dispositivo HID (role device) para que el PC ya
 * emparejado por el SO la vea como gamepad estándar: el juego recibe los
 * inputs directamente del HID del sistema, sin pasar por el Servidor PC
 * (viable incluso sin Wi-Fi — modo alternativa completa — y como failover
 * automático cuando la sesión LAN cae, SPEC-004 §8.3; ADR-009 §4).
 *
 * Prerrequisito: emparejamiento Bluetooth PC↔teléfono hecho una vez desde
 * el SO. Touch DRC, muestras inerciales y rumble NO viajan por esta ruta
 * (ADR-009 §5: exclusivos de la ruta LAN/SDK).
 */
class BluetoothHidGamepad(context: Context) {
    /** Estado del canal de la ruta BT. */
    enum class Channel {
        /** Sin perfil registrado ni conexión. */
        IDLE,

        /** Perfil registrado; esperando la conexión del host emparejado. */
        REGISTERED,

        /** Conectado al host emparejado: los reportes salen por BT. */
        CONNECTED,

        /** Arranque no viable (sin adaptador, sin permiso o rechazo). */
        FAILED,
    }

    /** Callbacks para enganchar el handover en la app (SPEC-004 §8.3). */
    interface Listener {
        /** Canal operativo: los inputs pueden salir por BT. */
        fun onChannelReady()

        /** Canal caído: cerrar la senda BT y decidir regreso a LAN. */
        fun onChannelLost()
    }

    private val appContext = context.applicationContext
    private var hidProfile: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val listenerRef = AtomicReference<Listener?>(null)
    private var channel = Channel.IDLE

    /** Estado observable del canal. */
    fun currentChannel(): Channel = channel

    /**
     * Registra el perfil HID y espera la conexión del host emparejado.
     * Asíncrono: el resultado llega por [Listener].
     */
    fun start(listener: Listener) {
        listenerRef.set(listener)
        val adapter: BluetoothAdapter? =
            appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "bt-hid: adaptador nulo o Bluetooth deshabilitado")
            channel = Channel.FAILED
            return
        }
        val requested =
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            Log.i(TAG, "bt-hid: el SO no soporta HID Device role (CTX-A2)")
            channel = Channel.FAILED
        }
    }

    /** Libera el proxy del perfil; la histéresis de salida la maneja la app. */
    fun stop() {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val profile = hidProfile
        if (adapter != null && profile != null) {
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, profile)
        }
        hidProfile = null
        host = null
        channel = Channel.IDLE
        listenerRef.get()?.onChannelLost()
    }

    /**
     * Envía el reporte de input codificado (viene de [SignatureHidCodec]).
     * La app solo lo llama con el canal CONNECTED (via Listener).
     */
    fun sendReport(bytes: ByteArray) {
        val profile = hidProfile
        val device = host
        if (profile == null || device == null) {
            Log.i(TAG, "bt-hid: sin conexión activa; reporte no enviado")
            return
        }
        try {
            profile.sendReport(device, REPORT_ID, bytes)
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT sin conceder: el canal no puede operar.
            Log.i(TAG, "bt-hid: sin permiso BLUETOOTH_CONNECT; reporte no enviado")
        }
    }

    private val profileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                val hidProxy = proxy as? BluetoothHidDevice
                if (hidProxy == null) {
                    channel = Channel.FAILED
                    return
                }
                hidProfile = hidProxy
                try {
                    hidProxy.registerApp(sdpSettings(), inQos(), inQos(), DIRECT_EXECUTOR, hidDeviceCallback)
                } catch (e: SecurityException) {
                    Log.i(TAG, "bt-hid: registro sin permiso: ${e.message}")
                    channel = Channel.FAILED
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                val wasConnected = channel == Channel.CONNECTED
                hidProfile = null
                host = null
                channel = Channel.IDLE
                if (wasConnected) {
                    listenerRef.get()?.onChannelLost()
                }
            }
        }

    private val hidDeviceCallback =
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(
                device: BluetoothDevice?,
                registered: Boolean,
            ) {
                if (!registered) {
                    Log.i(TAG, "bt-hid: registro HID no activo en el SO")
                    channel = Channel.FAILED
                    return
                }
                channel = Channel.REGISTERED
                connectToBondedHost()
            }

            override fun onConnectionStateChanged(
                device: BluetoothDevice?,
                state: Int,
            ) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        channel = Channel.CONNECTED
                        Log.i(TAG, "bt-hid: canal BT listo (host emparejado conectado)")
                        listenerRef.get()?.onChannelReady()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (channel == Channel.CONNECTED) {
                            host = null
                            channel = Channel.REGISTERED
                            Log.i(TAG, "bt-hid: canal BT perdido")
                            listenerRef.get()?.onChannelLost()
                        }
                    }
                }
            }
        }

    private fun connectToBondedHost() {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val device =
            adapter?.bondedDevices?.firstOrNull { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC }
        val profile = hidProfile
        if (device == null || profile == null) {
            Log.i(TAG, "bt-hid: sin PC emparejado vía Bluetooth; canal queda en espera")
            return
        }
        try {
            profile.connect(device)
        } catch (e: SecurityException) {
            Log.i(TAG, "bt-hid: conexión sin permiso: ${e.message}")
        }
    }

    /**
     * Descriptor SDP: la app se anuncia como gamepad HID con el Report Map
     * de la Signature (ADR-009 §4; descriptor de SPEC-004 §2 vía CR-059).
     */
    @Suppress("DEPRECATION")
    private fun sdpSettings(): BluetoothHidDeviceAppSdpSettings =
        BluetoothHidDeviceAppSdpSettings(
            APP_NAME,
            APP_DESCRIPTION,
            APP_PROVIDER,
            SUBCLASS_GAMEPAD,
            SignatureHidCodec.REPORT_MAP,
        )

    /** QoS en modo best effort sin reservas (margen defensivo del enlace). */
    @Suppress("DEPRECATION")
    private fun inQos(): BluetoothHidDeviceAppQosSettings =
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            TOKEN_RATE,
            TOKEN_BUCKET,
            PEAK_BANDWIDTH,
            LATENCY_VARIATION,
            DELAY_VARIATION,
        )

    private companion object {
        private const val TAG = "TwinpadBtHid"

        /** Report ID del input (el descriptor lo declara). */
        private const val REPORT_ID = 1

        /** Subclase SDP de gamepad (SUBCLASS2_*). */
        private const val SUBCLASS_GAMEPAD = BluetoothHidDevice.SUBCLASS2_GAMEPAD

        /** Identidad SDP de la app HID. */
        private const val APP_NAME = "Twinpad Gamepad"
        private const val APP_DESCRIPTION = "Twinpad phone as PC gamepad (CR-059)"
        private const val APP_PROVIDER = "Twinpad"

        /** QoS best effort razonable para el enlace HID clásico. */
        private const val TOKEN_RATE = 64 * 1024
        private const val TOKEN_BUCKET = 42
        private const val PEAK_BANDWIDTH = 10 * 1024
        private const val LATENCY_VARIATION = 640
        private const val DELAY_VARIATION = 640
    }
}

/** Ejecutor directo del binder: los callbacks corresponden al hilo del proxy. */
private val DIRECT_EXECUTOR = Executor { it.run() }
