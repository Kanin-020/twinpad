package com.twinpad.android.hid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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

        /** Vibración pedida por el host (CR-109): intensidades strong y weak, 0…255. */
        fun onRumble(
            strong: Int,
            weak: Int,
        ) {}
    }

    private val appContext = context.applicationContext
    private var originalAdapterName: String? = null
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
        // CR-122: sin BLUETOOTH_CONNECT (petición en curso) estas consultas
        // lanzan SecurityException; el canal queda FAILED y `onPermissionGranted`
        // lo rearma en cuanto el usuario concede el permiso.
        val enabled =
            try {
                adapter != null && adapter.isEnabled
            } catch (e: SecurityException) {
                false
            }
        if (adapter == null || !enabled) {
            Log.i(TAG, "bt-hid: adaptador nulo, Bluetooth deshabilitado o sin permiso")
            channel = Channel.FAILED
            return
        }
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Si la app se cerró sin restaurar, el nombre actual ya es ADAPTER_NAME: recupera el original.
        val pending = prefs.getString(KEY_ORIGINAL_NAME, null)
        if (pending != null) {
            try {
                adapter.setName(pending)
            } catch (e: SecurityException) {
                Log.i(TAG, "bt-hid: sin permiso para restaurar el nombre pendiente")
            }
            prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
        }
        try {
            if (originalAdapterName == null) {
                originalAdapterName = adapter.name
                prefs.edit().putString(KEY_ORIGINAL_NAME, originalAdapterName).apply()
            }
            // CR-122: solo se renombra si hace falta; un setName innecesario
            // reinicia el anuncio del adaptador y retrasa la conexión del PC.
            if (adapter.name != ADAPTER_NAME && !adapter.setName(ADAPTER_NAME)) {
                Log.i(TAG, "bt-hid: no se pudo poner el nombre $ADAPTER_NAME")
            }
        } catch (e: SecurityException) {
            Log.i(TAG, "bt-hid: sin permiso para leer o cambiar el nombre")
        }
        val requested =
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            Log.i(TAG, "bt-hid: el SO no soporta HID Device role (CTX-A2)")
            channel = Channel.FAILED
        }
    }

    /**
     * CR-122: reintenta el canal ya armado sin recrearlo (el permiso llegó en
     * caliente o la Activity se reabre). Antes se desregistraba y se creaba un
     * canal nuevo: eso cerraba el enlace HID recién abierto y dejaba el
     * registro anterior ocupado en el SO, con reintentos de 5-20 s.
     */
    fun retryNow() {
        when (channel) {
            Channel.CONNECTED -> Unit
            Channel.REGISTERED ->
                if (connectRetryJob != null) {
                    cancelConnectRetry()
                    connectToBondedHost()
                }
            Channel.IDLE, Channel.FAILED -> {
                if (hidProfile == null) return
                channel = Channel.IDLE
                cancelRegisterRetry()
                registerAttempts = 0
                registerNow()
            }
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
        restoreAdapterName()
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
        // Respaldo: si el callback llegó sin device, se toma el host conectado del perfil.
        val device = host ?: profile?.getConnectedDevices()?.firstOrNull()
        if (profile == null || device == null) {
            Log.i(
                TAG,
                "bt-hid: sin conexión activa (profile=${profile != null} host=${host != null}); reporte no enviado",
            )
            return
        }
        try {
            profile.sendReport(device, REPORT_ID, bytes.copyOfRange(1, bytes.size))
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
                    val accepted =
                        hidProxy.registerApp(sdpSettings(), inQos(), inQos(), DIRECT_EXECUTOR, hidDeviceCallback)
                    // CR-122: `registerApp` puede devolver false SIN callback
                    // (otra app ocupa el rol HID Device); antes ese caso moría
                    // en silencio y el gamepad no aparecía hasta reiniciar.
                    if (!accepted) registerNowAfterRejection()
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

            override fun onSetReport(
                device: BluetoothDevice?,
                type: Byte,
                id: Byte,
                data: ByteArray?,
            ) {
                // Report ID 3 = salida de vibración (CR-109); el resto se ignora.
                if (id.toInt() != RUMBLE_REPORT_ID || data == null || data.size < 2) return
                val strong = data[0].toInt() and BYTE_MASK
                val weak = data[1].toInt() and BYTE_MASK
                listenerRef.get()?.onRumble(strong, weak)
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
                        // CR-122: recordar el host que funcionó para la próxima.
                        device?.address?.let { prefs().edit().putString(KEY_LAST_HOST, it).apply() }
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
            val accepted =
                profile.registerApp(sdpSettings(), inQos(), inQos(), DIRECT_EXECUTOR, hidDeviceCallback)
            if (!accepted) registerNowAfterRejection()
        } catch (e: SecurityException) {
            Log.i(TAG, "bt-hid: reintento sin permiso: ${e.message}")
        }
    }

    /**
     * CR-122: el SO rechazó el registro (return false). Puede ser un rechazo
     * real o el callback `registered=true` en vuelo (registro justo antes por
     * otro proxy): si el canal ya está registrado o conectado NO se toca; si
     * sigue en IDLE/FAILED, el registro que ocupa el rol puede ser huérfano de
     * un proceso anterior de la misma app (force-stop no lo libera). Como el
     * UID es el mismo, [BluetoothHidDevice.unregisterApp] lo libera al momento
     * y el registro se reintenta tras una pausa corta; si el rol lo detenta
     * otra app, `unregisterApp` devuelve false y se mantiene el backoff.
     */
    private fun registerNowAfterRejection() {
        if (channel == Channel.REGISTERED || channel == Channel.CONNECTED) return
        Log.i(TAG, "bt-hid: el SO rechazó el registro HID; se libera el registro anterior")
        channel = Channel.IDLE
        val released =
            try {
                hidProfile?.unregisterApp() == true
            } catch (e: SecurityException) {
                false
            }
        if (released) {
            Log.i(TAG, "bt-hid: registro anterior liberado; se vuelve a registrar")
            registerAttempts = 0
            scheduleRegisterRetry(REGISTER_AFTER_RELEASE_MS)
        } else {
            scheduleRegisterRetry()
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
    private fun scheduleRegisterRetry(fixedDelayMs: Long? = null) {
        // CR-122: ya hay un reintento armado; reprogramarlo solo alargaría la espera.
        if (registerRetryJob != null) return
        val delayMs =
            fixedDelayMs ?: run {
                registerAttempts += 1
                minOf(REGISTER_RETRY_BASE_MS shl (registerAttempts - 1), REGISTER_RETRY_MAX_MS)
            }
        val ms = if (delayMs < MS_PER_SECOND) "$delayMs ms" else "${delayMs / MS_PER_SECOND} s"
        Log.i(TAG, "bt-hid: reintento de registro en $ms (intento $registerAttempts)")
        cancelRegisterRetry()
        registerRetryJob =
            registerRetryExecutor.schedule(
                {
                    registerRetryJob = null
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
        // CR-120: se intenta con TODOS los emparejados clásicos en cada ronda.
        // Antes se probaba uno por reintento con backoff 4 s → 20 s: con varios
        // equipos emparejados (coche, auriculares, otro PC) el host correcto
        // quedaba al final de la rotación y Steam tardaba minutos en listarlo.
        // CR-122: el host que conectó la última vez va primero, así la
        // reconexión normal no espera a los demás emparejados.
        val bonded =
            try {
                adapter?.bondedDevices?.filter { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC }
            } catch (e: SecurityException) {
                Log.i(TAG, "bt-hid: sin permiso para listar los emparejados")
                null
            }
        val profile = hidProfile
        if (bonded.isNullOrEmpty() || profile == null) {
            Log.i(TAG, "bt-hid: sin PC emparejado vía Bluetooth; canal queda en espera")
            return
        }
        // CR-122: los equipos AUDIO_VIDEO (radio del coche, auriculares) no
        // aceptan HID y cada intento gasta un page timeout antes de llegar al
        // PC; se descartan mientras quede algún candidato plausible.
        val plausible = bonded.filter { HidHostOrder.isLikelyHost(majorClass(it)) }
        val pool = plausible.ifEmpty { bonded }
        if (pool.size < bonded.size) {
            Log.i(TAG, "bt-hid: se omiten ${bonded.size - pool.size} equipos de audio emparejados")
        }
        val byAddress = pool.associateBy { it.address }
        val candidates =
            HidHostOrder
                .order(pool.map { it.address }, prefs().getString(KEY_LAST_HOST, null))
                .mapNotNull { byAddress[it] }
        Log.i(TAG, "bt-hid: conectando HID a ${candidates.joinToString { it.name ?: it.address }}")
        var requested = false
        for (device in candidates) {
            try {
                requested = profile.connect(device) || requested
            } catch (e: SecurityException) {
                Log.i(TAG, "bt-hid: conexión sin permiso: ${e.message}")
                return
            }
        }
        // CR-122: si el SO no aceptó ningún intento no habrá callbacks de
        // estado; sin esto la ronda quedaba muerta hasta reiniciar la app.
        if (!requested) scheduleConnectRetry()
    }

    /** Reintenta la conexión al host con tope de 20 s, indefinido: el
     * rechazo o aborto del enlace HID (L2CAP) puede resolverse al
     * reintentar (el host aún no catálogo el gamepad — CR-059). */
    private fun scheduleConnectRetry() {
        // CR-122: con varios emparejados llegan varios DISCONNECTED seguidos;
        // reprogramar en cada uno retrasaba la ronda en curso (1,5 s por evento)
        // y saltaba el backoff hasta el tope.
        if (connectRetryJob != null) return
        connectAttempts += 1
        val exponential = CONNECT_RETRY_BASE_MS shl (connectAttempts - 1)
        val delayMs = minOf(exponential, CONNECT_RETRY_MAX_MS)
        Log.i(TAG, "bt-hid: enlace al host caído; reintento en ${delayMs / MS_PER_SECOND} s (intento $connectAttempts)")
        cancelConnectRetry()
        connectRetryJob =
            registerRetryExecutor.schedule(
                {
                    connectRetryJob = null
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

    /** Clase mayor Bluetooth del equipo (MISC si el SO no la informa). */
    private fun majorClass(device: BluetoothDevice): Int =
        try {
            device.bluetoothClass.majorDeviceClass
        } catch (e: SecurityException) {
            BluetoothClass.Device.Major.MISC
        }

    /** Preferencias privadas del canal BT HID (nombre original y último host). */
    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Devuelve el nombre Bluetooth original del teléfono si se cambió a [ADAPTER_NAME]. */
    private fun restoreAdapterName() {
        val original = originalAdapterName ?: FALLBACK_ADAPTER_NAME
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            adapter?.setName(original)
        } catch (e: SecurityException) {
            Log.i(TAG, "bt-hid: sin permiso para restaurar el nombre")
        }
        originalAdapterName = null
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ORIGINAL_NAME).apply()
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
        private const val RUMBLE_REPORT_ID = 3

        /** Máscara de un byte (0xFF) para leer la fuerza de vibración sin signo. */
        private const val BYTE_MASK = 0xFF

        /** Subclase SDP de gamepad (SUBCLASS2_*). */
        private const val SUBCLASS_GAMEPAD = BluetoothHidDevice.SUBCLASS2_GAMEPAD

        /** Identidad SDP de la app HID. */
        private const val APP_NAME = "Twinpad Gamepad"

        /** Nombre Bluetooth del teléfono mientras Twinpad está activo (Steam lo muestra). */
        private const val ADAPTER_NAME = "Twinpad"

        /** Nombre Bluetooth del teléfono de prueba; respaldo si no hay original guardado. */
        private const val FALLBACK_ADAPTER_NAME = "Android"

        /** Preferencias para recuperar el nombre original tras un cierre sin restaurar. */
        private const val PREFS = "twinpad_bt_hid"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"

        /** CR-122: último host que conectó el canal HID (va primero al reconectar). */
        private const val KEY_LAST_HOST = "last_hid_host"
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

        /** CR-122: pausa para que el SO cierre el registro liberado. */
        private const val REGISTER_AFTER_RELEASE_MS = 250L

        /** Tope del backoff de registro: 20 s por intento, indefinido. */
        private const val REGISTER_RETRY_MAX_MS = 20_000L

        /** Backoff de la conexión al host: 1,5 s base con doble exponencial. */
        private const val CONNECT_RETRY_BASE_MS = 1_500L

        /** Tope del backoff de conexión: 8 s por intento, indefinido. */
        private const val CONNECT_RETRY_MAX_MS = 8_000L

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
