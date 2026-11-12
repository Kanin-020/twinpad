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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
    private var registerAttempts = 0
    private var registerRetryJob: ScheduledFuture<*>? = null
    private var connectAttempts = 0
    private var connectRetryJob: ScheduledFuture<*>? = null

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
        cancelRegisterRetry()
        cancelConnectRetry()
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val profile = hidProfile
        if (profile != null) {
            try {
                profile.unregisterApp()
            } catch (e: SecurityException) {
                // BLUETOOTH_CONNECT sin conceder: no hay registro que liberar.
            }
        }
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
                cancelRegisterRetry()
                registerAttempts = 0
                try {
                    hidProxy.registerApp(sdpSettings(), inQos(), inQos(), DIRECT_EXECUTOR, hidDeviceCallback)
                } catch (e: SecurityException) {
                    Log.i(TAG, "bt-hid: registro sin permiso: ${e.message}")
                    channel = Channel.FAILED
                }
            }

            @Suppress("SwallowedException")
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
                    Log.i(TAG, "bt-hid: registro HID no activo en el SO; reintentando")
                    // El registro se perdió: sin volver a IDLE el reintento no actúa.
                    cancelConnectRetry()
                    host = null
                    channel = Channel.IDLE
                    scheduleRegisterRetry()
                    return
                }
                registerAttempts = 0
                cancelRegisterRetry()
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
                        connectAttempts = 0
                        cancelConnectRetry()
                        Log.i(TAG, "bt-hid: canal BT listo (host emparejado conectado)")
                        listenerRef.get()?.onChannelReady()
                    }

                    BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTING,
                    -> Log.i(TAG, "bt-hid: estado de enlace HID con el host: $state")

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (channel == Channel.CONNECTED) {
                            host = null
                            channel = Channel.REGISTERED
                            Log.i(TAG, "bt-hid: canal BT perdido")
                            listenerRef.get()?.onChannelLost()
                        } else if (channel == Channel.REGISTERED) {
                            // El intento inicial (o un reintento) no llegó a
                            // CONECTED: el host rechazó o abortó el enlace.
                            scheduleConnectRetry()
                        }
                    }
                }
            }
        }

    private fun registerNow() {
        val profile = hidProfile ?: return
        try {
            profile.registerApp(sdpSettings(), inQos(), inQos(), DIRECT_EXECUTOR, hidDeviceCallback)
        } catch (e: SecurityException) {
            Log.i(TAG, "bt-hid: reintento sin permiso: ${e.message}")
        }
    }

    /**
     * Reintenta el registro con backoff y tope de 20 s, indefinido (CR-059):
     * [BluetoothHidDevice.registerApp] puede no producir callback (otra app
     * detenta el rol HID Device — solo una app a la vez), desregistrar en
     * caliente (el SO lo hace al re-emparejar) o fallar en silencio. Sin
     * registro persistente el gamepad desaparece para el host: el rol se
     * reclama en cuanto se libera, sin techo de intentos.
     */
    private fun scheduleRegisterRetry() {
        registerAttempts += 1
        val exponential = REGISTER_RETRY_BASE_MS shl (registerAttempts - 1)
        val delayMs = minOf(exponential, REGISTER_RETRY_MAX_MS)
        Log.i(TAG, "bt-hid: reintento de registro en ${delayMs / MS_PER_SECOND} s (intento $registerAttempts)")
        cancelRegisterRetry()
        registerRetryJob =
            registerRetryExecutor.schedule(
                {
                    if (channel == Channel.IDLE || channel == Channel.FAILED) {
                        channel = Channel.IDLE
                        registerNow()
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun cancelRegisterRetry() {
        registerRetryJob?.cancel(false)
        registerRetryJob = null
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

    /** Reintenta la conexión al host con tope de 20 s, indefinido: el
     * rechazo o aborto del enlace HID (L2CAP) puede resolverse al
     * reintentar (el host aún no catálogo el gamepad — CR-059). */
    private fun scheduleConnectRetry() {
        connectAttempts += 1
        val exponential = CONNECT_RETRY_BASE_MS shl (connectAttempts - 1)
        val delayMs = minOf(exponential, CONNECT_RETRY_MAX_MS)
        Log.i(TAG, "bt-hid: enlace al host caído; reintento en ${delayMs / MS_PER_SECOND} s (intento $connectAttempts)")
        cancelConnectRetry()
        connectRetryJob =
            registerRetryExecutor.schedule(
                {
                    if (channel == Channel.REGISTERED) {
                        connectToBondedHost()
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun cancelConnectRetry() {
        connectRetryJob?.cancel(false)
        connectRetryJob = null
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

        /** Backoff del reintento de registro: 5 s base con doble exponencial. */
        private const val REGISTER_RETRY_BASE_MS = 5_000L

        /** Tope del backoff de registro: 20 s por intento, indefinido. */
        private const val REGISTER_RETRY_MAX_MS = 20_000L

        /** Backoff de la conexión al host: 4 s base con doble exponencial. */
        private const val CONNECT_RETRY_BASE_MS = 4_000L

        /** Tope del backoff de conexión: 20 s por intento, indefinido. */
        private const val CONNECT_RETRY_MAX_MS = 20_000L

        /** División ms → s para el log de reintentos. */
        private const val MS_PER_SECOND = 1000

        /** Ejecutor del reintento (un solo hilo; el callback decide el turno). */
        private val registerRetryExecutor =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "twinpad-bt-hid-retry").apply { isDaemon = true }
            }
    }
}

/** Ejecutor directo del binder: los callbacks corresponden al hilo del proxy. */
private val DIRECT_EXECUTOR = Executor { it.run() }
