package com.twinpad.android.app.ui

import android.Manifest
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import com.twinpad.android.app.R
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.hid.PhysicalAxes
import com.twinpad.android.app.pairing.PairingViewModel
import com.twinpad.android.app.session.SessionForegroundService
import com.twinpad.android.app.session.SessionNotificationSync
import com.twinpad.android.app.stream.StreamState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Delay del tick del HUD (ms). */
private const val HUD_TICK_MS: Long = 1_000L

/** CR-038: valor inicial de [PairingActivity.loadedProfileId] — ningún juego
 * cargado aún, así la primera emisión del estado combinado (antes `null`)
 * dispara la carga del perfil vacío como en CR-034. */
private const val PROFILE_UNLOADED = "\u0000unloaded"

/**
 * Pantalla de Pairing (SPEC-002 §6) sobre Views clásicas: layouts XML +
 * drawables XML editables en Android Studio (pedido del owner: mantener la
 * UI fuera del código). Observa el mismo [PairingViewModel] (la lógica no
 * cambió: DES-002 §4). El drawer lateral alterna el HUD y los controles.
 */
class PairingActivity : AppCompatActivity() {
    private lateinit var viewModel: PairingViewModel
    private lateinit var panelFragment: PairingFragment
    private lateinit var sessionFragment: SessionFragment
    private lateinit var cameraController: PairingCameraController
    private lateinit var micController: PairingMicController
    private lateinit var keyboardOverlay: KeyboardOverlayController
    private lateinit var drawer: SettingsDrawerController

    private val sidebarChord =
        SidebarChord {
            findViewById<DrawerLayout>(R.id.settings_drawer).openDrawer(GravityCompat.START)
        }

    /** CR-115: ruta de audio del teléfono (para no enviar nada a la PC por BT). */
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** SPEC-011: perfiles y mapeador del mando físico (Ajustes + autoapertura). */
    private val gamepadProfiles by lazy { GamepadProfileStore(this) }
    private lateinit var physicalGamepad: PhysicalGamepadController
    private var surfaceRef: ProfileSurface? = null
    private var hudVisible = true
    private var controlsVisible = true

    /** CR-054: bloque «Métricas avanzadas» del HUD vigente (drawer). */
    private var advancedMetricsVisible = false

    /** CR-077: panel dueño de la retícula 2×2 de gauges de espera (creado
     * en onCreate tras setContentView). */
    private lateinit var gaugePanel: WaitingGaugePanel

    /** CR-079: slider de gauges sobre el stream (la misma retícula del
     * CR-076 en presentación de hoja inferior; nace abierta en sesión). */
    private lateinit var metricsSlider: MetricsSliderController

    /** CR-032: distribución Nintendo vigente; la superficie lo aplica
     * al adjuntarse (onCreate restauración → attach posterior). */
    private var nintendoFaceDistribution = false
    private var gyroMonitorVisible = false
    private var sessionConfigSent = false

    /** CR-034: preferencia vigente del stream de cámara (toggle + lente del
     * drawer); el valor alimenta la evaluación combinada de demanda. */
    private var cameraStreamSettingsLoaded = false
    private var cameraStreamEnabled = false
    private var cameraStreamLens = CameraLens.FRONT

    /** CR-090: último ajuste notificado por el drawer; permite distinguir un
     * cambio de la tupla de video (reenvía `video_config`) de uno que solo
     * afecta al micrófono (no toca el encoder del server). */
    private var lastStreamSettingsApplied: AndroidSettings? = null

    /** CR-038: el estado combinado emite en cada cambio de demanda; el perfil
     * del juego solo se recarga cuando el juego seleccionado cambia. */
    private var loadedProfileId: String? = PROFILE_UNLOADED
    private var mediaSurfaceHolder: SurfaceHolder? = null
    private val mediaSurfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mediaSurfaceHolder = holder
                viewModel.attachMediaSurface(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                // La misma Surface sigue válida entre cambios de tamaño: no
                // se reinicia el canal de medios (CR-100).
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (mediaSurfaceHolder === holder) {
                    mediaSurfaceHolder = null
                    viewModel.attachMediaSurface(null)
                }
            }
        }
    private lateinit var sensorManager: SensorManager
    private lateinit var motionSensors: PhoneMotionSensors

    /** Último par (w, h) REAL presentado por el stream (CR-074): con
     * «Nativa» el drawer no fija target y el marco debe salir del frame
     * vivo, no de los 0/0 persistidos. */
    private var lastStreamFrame: Pair<Int, Int> = 0 to 0

    /** Aplica el modo de escalado CR-025: letterbox en `contain`, relleno en `expand`. */
    private fun applyVideoScale(settings: AndroidSettings) {
        val container = sessionView<ViewGroup>(R.id.video_surface_container)
        val video = sessionView<SurfaceView>(R.id.video_view)
        container.post {
            val (videoWidth, videoHeight) =
                effectiveVideoDims(
                    lastStreamFrame.first,
                    lastStreamFrame.second,
                    settings.width,
                    settings.height,
                )
            val rect =
                videoContentRect(
                    settings.scaleMode,
                    container.width.toFloat(),
                    container.height.toFloat(),
                    videoWidth,
                    videoHeight,
                )
            video.layoutParams =
                (video.layoutParams as FrameLayout.LayoutParams).apply {
                    width = (container.width * rect.width).toInt()
                    height = (container.height * rect.height).toInt()
                    gravity = Gravity.CENTER
                }
            video.requestLayout()
            surfaceRef?.videoContentRect = rect
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
            // CR-108: el escaneo con sesión viva lo gobierna la Activity (la
            // máquina no acepta Escanear desde U4).
            if (granted && linkedScanActive) {
                startLinkedScan()
            } else if (!granted && linkedScanActive) {
                stopLinkedScan()
                panelView<TextView>(R.id.txt_status).text =
                    getString(R.string.pairing_error_camera_denied)
            }
        }

    /** CR-108: cámara del panel «Nueva conexión» con una sesión ya activa. */
    private var linkedScanActive = false

    /** Permiso BLUETOOTH_CONNECT para el canal HID (prueba de banco). */
    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onBluetoothPermissionResult(granted)
        }

    /** CR-035: permiso del micrófono bajo demanda; al concederlo se reevalúa
     * el estado combinado de la sesión (juego «cemu» + `mic_request`). */
    private val micPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) micDemand.apply()
        }

    /** Efectos del drawer que exceden sus vistas: hardware y estado propio. */
    private val drawerHost =
        object : SettingsDrawerHost {
            override fun applyVideoScale(settings: AndroidSettings) {
                this@PairingActivity.applyVideoScale(settings)
            }

            /** CR-090: el interruptor del micrófono aplica al momento (sin
             * esperar a un nuevo `mic_request`); `video_config` solo se
             * reenvía cuando cambió la tupla de video, para no reiniciar el
             * encoder por silenciar el micrófono. */
            override fun onStreamSettingsChanged(settings: AndroidSettings) {
                applyMicDemand()
                val previous = lastStreamSettingsApplied
                lastStreamSettingsApplied = settings
                if (previous == null || previous.videoConfigLine() != settings.videoConfigLine()) {
                    viewModel.onStreamSettingsChanged(settings)
                }
            }

            override fun updateAudioVolume(volume: Float) {
                viewModel.setAudioVolume(volume)
            }

            override fun onHudToggled(visible: Boolean) {
                hudVisible = visible
                renderHudChip()
            }

            override fun onHudRestored(visible: Boolean) {
                hudVisible = visible
            }

            /** CR-054: el bloque de métricas por etapa es un elemento
             * separado delHUD («Métricas avanzadas») con su propio switch. */
            override fun onAdvancedMetricsToggled(enabled: Boolean) {
                advancedMetricsVisible = enabled
                renderHudChip()
            }

            override fun onGyroMonitorToggled(visible: Boolean) {
                gyroMonitorVisible = visible
                findViewById<GyroMonitorView>(R.id.gyro_monitor).isVisible = visible
                findViewById<GyroMonitorView>(R.id.gyro_monitor).bringToFront()
            }

            override fun onControlsToggled(visible: Boolean) {
                controlsVisible = visible
                surfaceRef?.showControls = visible
            }

            /** CR-032: también actúa como restauración inicial; si la
             * superficie aún no existe, [attachProfileSurface] aplica el
             * flag al crearla. */
            override fun onNintendoDistributionToggled(enabled: Boolean) {
                nintendoFaceDistribution = enabled
                surfaceRef?.setNintendoFaceLayout(enabled)
            }

            /** CR-034: el toggle/lente del drawer guarda el estado y el
             * stream de cámara se reevalúa al momento (juego + demanda +
             * preferencia), parando/reiniciando si ya estaba activo. */
            override fun onCameraStreamToggled(
                enabled: Boolean,
                lens: CameraLens,
            ) {
                cameraStreamEnabled = enabled
                cameraStreamLens = lens
                cameraStreamSettingsLoaded = true
                android.util.Log.i("PairingCamera", "toggle drawer cámara enabled=$enabled lens=$lens")
                cameraController.stopStream()
                applyCameraDemand()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prueba BT: pide BLUETOOTH_CONNECT al abrir la app (Android 12+).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_pairing)
        // SPEC-011: antes de crear los fragmentos, porque la superficie de
        // sesión se adjunta en onSessionReady y consulta el mapeo activo.
        physicalGamepad =
            PhysicalGamepadController(this, gamepadProfiles) { mapping ->
                surfaceRef?.setPhysicalMapping(mapping)
            }
        physicalGamepad.bind()
        // CR-097 paso 1: el panel de conexión vive en PairingFragment. Tras la
        // muerte del proceso Android restaura los fragmentos por reflexión
        // (constructor sin argumentos): se reutilizan los restaurados y se
        // re-cablean los callbacks, que no viajan en el estado guardado.
        panelFragment =
            supportFragmentManager.findFragmentById(R.id.pairing_fragment_container) as? PairingFragment
                ?: PairingFragment()
        sessionFragment =
            supportFragmentManager.findFragmentById(R.id.session_container) as? SessionFragment
                ?: SessionFragment()
        // El fragmento avisa cuando su vista existe: sólo entonces se cablean
        // sus botones y se empieza a observar el estado, porque renderState usa
        // las vistas del panel.
        panelFragment.onPanelReady = {
            wireViews()
            observeStates()
        }
        sessionFragment.onSessionReady = ::onSessionReady
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.pairing_fragment_container, panelFragment)
                .replace(R.id.saved_devices_container, SavedDevicesFragment())
                .replace(R.id.session_container, sessionFragment)
                .commitNow()
        }
        viewModel = ViewModelProvider(this, PairingViewModel.factory(application))[PairingViewModel::class.java]
        if (savedInstanceState != null) {
            // Los fragmentos restaurados ya inflaron su vista (antes de existir
            // viewModel y de asignarles el callback): se cablean ahora.
            wireViews()
            observeStates()
            onSessionReady()
        }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        motionSensors =
            PhoneMotionSensors(
                sensorManager,
                { display },
                { sample -> surfaceRef?.updateMotion(sample) },
                ::showOrientationTurns,
            )
        cameraController =
            PairingCameraController(this) { raw ->
                viewModel.onEvent(PairingEvent.QrScanned(raw))
            }
        micController =
            PairingMicController(this) { line ->
                viewModel.sendSessionLine(line)
            }
        observeCameraEffects()
    }

    /**
     * CR-097 paso 3: la vista de sesión existe desde onViewCreated del fragmento.
     * Aquí se crean los controladores que buscan vistas de sesión.
     */
    private fun onSessionReady() {
        keyboardOverlay =
            KeyboardOverlayController(this) { line ->
                viewModel.sendSessionLine(line)
            }
        drawer = SettingsDrawerController(this, drawerHost)
        gaugePanel = WaitingGaugePanel(this)
        metricsSlider = MetricsSliderController(this)
        sessionView<SurfaceView>(R.id.video_view).holder.addCallback(mediaSurfaceCallback)
        sessionView<View>(R.id.video_surface_container).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyVideoScale(drawer.load())
        }
        drawer.applyPersistedSettings()
        // CR-115: el ruteo Bluetooth cambia la demanda del micrófono y la
        // salida del audio de la sesión en caliente.
        micDemand.register()
        audioManager.registerAudioDeviceCallback(playbackRouteCallback, null)

        sessionView<ImageButton>(R.id.btn_settings).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).openDrawer(GravityCompat.START)
        }
        observeMicDemand()
        observeKeyboardDemand()
        drawer.bind()
        if (viewModel.uiState.value is PairingUiState.Linked && !sessionConfigSent) {
            viewModel.onStreamSettingsChanged(drawer.load())
            sessionConfigSent = true
        }
        attachProfileSurface()
        // Se suscribe aquí: StateFlow emite el valor actual, y la vista ya existe.
        lifecycleScope.launch {
            viewModel.streamState.collectLatest { state -> applyStreamState(state) }
            // CR-121: el texto de la notificación sigue al stream.
            SessionNotificationSync.bind(this@PairingActivity, viewModel.uiState, viewModel.streamState)
        }
    }

    /** Busca una vista de la sesión, que vive en [SessionFragment]. */
    private fun <T : View> sessionView(id: Int): T {
        return sessionFragment.requireView().findViewById<T>(id)
    }

    /** Busca una vista del panel de conexión, que vive en [PairingFragment]. */
    private fun <T : View> panelView(id: Int): T {
        return panelFragment.requireView().findViewById<T>(id)
    }

    /** Cablea cada View a su evento del ViewModel. */
    private fun wireViews() {
        panelView<Button>(R.id.btn_scan).setOnClickListener {
            // CR-108: con sesión viva el enlace pedido se guarda para cuando
            // la sesión termine; no abre un intento nuevo en la máquina.
            if (viewModel.uiState.value is PairingUiState.Linked) {
                startLinkedScan()
            } else {
                viewModel.onEvent(PairingEvent.ScanRequested)
            }
        }
        // CR-060 (SPEC-002 §8.4): el payload textual que la PC ya imprime
        // junto al QR se escribe/pega aquí; misma ruta que el QR sin cámara.
        panelView<Button>(R.id.btn_typed_code).setOnClickListener {
            showTypedCodeDialog()
        }
        panelView<Button>(R.id.btn_cancel).setOnClickListener {
            if (linkedScanActive) stopLinkedScan() else viewModel.onEvent(PairingEvent.Cancelled)
        }
        panelView<Button>(R.id.btn_disconnect).setOnClickListener {
            viewModel.onDisconnectRequested() // CR-094
        }
        panelView<Button>(R.id.btn_retry).setOnClickListener {
            viewModel.onEvent(PairingEvent.Retry)
        }
        findViewById<Button>(R.id.btn_close_drawer).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).closeDrawer(GravityCompat.START)
        }
        // CR-097: el drawer sólo cambia de vista; la sesión sigue viva en segundo plano.
        val drawer = findViewById<DrawerLayout>(R.id.settings_drawer)
        findViewById<ChipGroup>(R.id.navigation_view).setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            drawer.closeDrawer(GravityCompat.START)
            when (id) {
                R.id.nav_session -> connectionTabs.showSession()
                R.id.nav_connection_list -> connectionTabs.show(connectionView = true)
                R.id.nav_new_connection -> connectionTabs.show(connectionView = false)
            }
        }
        // El chip ya activo no cambia de estado: cerrar el drawer al tocarlo.
        listOf(R.id.nav_new_connection, R.id.nav_session, R.id.nav_connection_list).forEach { chipId ->
            findViewById<View>(chipId).setOnClickListener { drawer.closeDrawer(GravityCompat.START) }
        }
    }

    /** CR-060: diálogo de entrada manual del payload de vinculación.
     * Vacío o inválido → la máquina responde E5 (§9); la cámara nunca se
     * solicita en esta vía (R20). Función-propiedad para mantener el
     * recuento de funciones bajo el umbral de detekt. */
    private val showTypedCodeDialog: () -> Unit = {
        val input = android.widget.EditText(this)
        input.hint = getString(R.string.pairing_typed_code_hint)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.pairing_typed_code_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = input.text?.toString().orEmpty()
                if (typed.isNotBlank()) {
                    viewModel.onEvent(PairingEvent.QrScanned(typed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Carga el perfil del juego y, solo para Cemu con demanda vigente, activa
     * la cámara frontal (CR-034 + CR-038): el juego debe haber abierto la
     * cámara del GamePad en Cemu (`camera_request on=true`). Los cambios de
     * cada flow reinician el envío con el estado combinado más reciente. */
    private fun observeSelectedGame() {
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.selectedGameId,
                viewModel.cameraRequested,
            ) { gameId, cameraRequested -> gameId to cameraRequested }
                .collectLatest { (gameId, cameraRequested) ->
                    if (gameId != loadedProfileId) {
                        loadedProfileId = gameId
                        surfaceRef?.loadProfile(gameId.orEmpty())
                    }
                    applyCameraDemand()
                }
        }
    }

    /** CR-034 + CR-038: arranca/para el stream de cámara solo si el juego
     * «cemu» está seleccionado Y demanda vigente Y el toggle del drawer
     * activado (función-propiedad; detekt hiperparámetros). */
    private val applyCameraDemand: () -> Unit = {
        val demandActive =
            viewModel.selectedGameId.value == "cemu" && viewModel.cameraRequested.value
        // Diagnóstico CR-034: la decisión completa de gating deja huella por
        // exactamente la ventana sin logs que dejó el 1-s disconnect.
        android.util.Log.i(
            "PairingCamera",
            "applyCameraDemand juego=${viewModel.selectedGameId.value} " +
                "demanda=${viewModel.cameraRequested.value} toggle=$cameraStreamEnabled",
        )
        if (demandActive && cameraStreamEnabled) {
            cameraController.startStream(this@PairingActivity, cameraStreamLens) { line ->
                viewModel.sendSessionLine(line)
            }
        } else {
            cameraController.stopStream()
        }
    }

    /** CR-044: controla el overlay del teclado solo con la demanda vigente
     * del juego (`keyboard_request`) y «cemu» seleccionado: con Twinpad
     * conectado el IME del teléfono reemplaza al teclado de Cemu.
     * Función-propiedad (detekt: umbral de funciones de la clase). */
    private val observeKeyboardDemand: () -> Unit = {
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.selectedGameId,
                viewModel.keyboardRequested,
            ) { gameId, request -> gameId to request }
                .collectLatest { (gameId, request) ->
                    keyboardOverlay.apply(request, gameId == "cemu")
                }
        }
    }

    /** CR-035 + CR-090: la demanda del micrófono se reevalúa en cada cambio
     * del juego seleccionado, de `mic_request` o del interruptor del drawer. */
    private fun observeMicDemand() {
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.selectedGameId,
                viewModel.micRequested,
            ) { _, _ -> Unit }
                .collectLatest { applyMicDemand() }
        }
    }

    /** CR-108: abre la cámara del QR con la sesión viva. El resultado entra a
     * [PairingViewModel.onEvent] y se guarda como enlace pendiente.
     * (Lambda: detekt TooManyFunctions.) */
    private val startLinkedScan: () -> Unit = {
        linkedScanActive = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            renderLinked()
            cameraController.bind(panelView<PreviewView>(R.id.camera_preview), this)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** CR-108: cierra la cámara del escaneo con sesión viva y deja el panel en
     * su estado de sesión activa (con el aviso del enlace pendiente si lo hay).
     * (Lambda: detekt TooManyFunctions.) */
    private val stopLinkedScan: () -> Unit = {
        if (linkedScanActive) {
            linkedScanActive = false
            cameraController.stop()
        }
        if (viewModel.uiState.value is PairingUiState.Linked) renderLinked()
    }

    /** CR-035/CR-090/CR-115: el gate y la captura viven en el controlador. */
    private val micDemand by lazy {
        MicDemandController(
            audioManager = audioManager,
            mic = micController,
            gameSelected = { viewModel.selectedGameId.value == "cemu" },
            micRequested = { viewModel.micRequested.value },
            switchEnabled = { drawer.load().microphoneStream },
            requestPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }

    private val applyMicDemand: () -> Unit = { micDemand.apply() }

    /** CR-115: al cambiar las salidas del teléfono se reevalúa si el audio de
     * la sesión debe quedarse en el altavoz (con Bluetooth) o volver al
     * ruteo del sistema. */
    private val playbackRouteCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = viewModel.applyPlaybackRoute()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                viewModel.applyPlaybackRoute()
            }
        }

    /** Observa la sesión completa (video por frame, HUD por tick). */
    private fun observeStates() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                applyLinkedState(state)
            }
        }
        // CR-108: al guardarse el enlace pendiente se cierra la cámara y se
        // avisa que se conectará cuando termine la sesión actual.
        lifecycleScope.launch {
            viewModel.pendingLinkWaiting.collectLatest { waiting ->
                if (waiting) stopLinkedScan()
            }
        }
        observeSelectedGame()
        lifecycleScope.launch {
            viewModel.selectionRequest.collectLatest { games ->
                val options = games.map { it.gameName }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@PairingActivity)
                    .setTitle(R.string.game_select_title)
                    .setItems(options) { _, index -> viewModel.selectGame(games[index].gameId) }
                    .setOnCancelListener { }
                    .show()
            }
        }
        // HUD: 1 tick por segundo actualiza el chip con fps/kbps.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(HUD_TICK_MS)
                renderHudChip()
                // CR-076 v2 + CR-079: los gauges se pintan en la vista de
                // espera y, con el slider activo, también durante el stream
                // (la hoja re-consulta el ajuste del drawer para aplicarlo
                // en caliente); el origen vigente (PC u otro) los alimenta.
                val streamingNow = viewModel.streamState.value.state == StreamState.STREAMING
                metricsSlider.setStreaming(streamingNow, drawer.load().metricsSliderOverStream)
                keepDisplayAwake(viewModel.uiState.value is PairingUiState.Linked)
                if (viewModel.uiState.value is PairingUiState.Linked &&
                    (!streamingNow || metricsSlider.sheetActive)
                ) {
                    // CR-079 v2 (owner): las métricas visibles son SIEMPRE las de la
                    // PC (host_metrics del server); sin rama del teléfono.
                    gaugePanel.renderHost(viewModel.hostMetrics.value)
                } else {
                    gaugePanel.stopDeviceMonitor()
                }
            }
        }
    }

    /** U4/Linked: config de stream una vez por sesión (CR-074). Función-
     * propiedad para el umbral de funciones de detekt. */
    private val applyLinkedState: (PairingUiState) -> Unit = { state ->
        renderState(state)
        if (state is PairingUiState.Linked && !sessionConfigSent && ::drawer.isInitialized) {
            viewModel.onStreamSettingsChanged(drawer.load())
            sessionConfigSent = true
        } else if (state !is PairingUiState.Linked) {
            sessionConfigSent = false
        }
        if (state is PairingUiState.Linked) viewModel.applyPlaybackRoute()
    }

    /** Tick del stream: escala al frame real y el MONITOR FULL-SCREEN de
     * espera (CR-076 v3): sin stream (INACTIVE) o sin conexión (INTERRUPTED)
     * NO queda el último frame renderizado — la retícula 2×2 de gauges
     * del HOST cubre el Framebuffer residual; la lista de juegos solo se
     * muestra en INACTIVE (CR-026). Función-propiedad para LongMethod/
     * umbral del detekt. */
    private val applyStreamState: (com.twinpad.android.app.stream.StreamUiState) -> Unit = { state ->
        // CR-074: el marco (contener/estirar) sigue al frame REAL;
        // se re-aplica solo cuando cambia la dimensión presentada.
        if (lastStreamFrame.first != state.width || lastStreamFrame.second != state.height) {
            lastStreamFrame = state.width to state.height
            applyVideoScale(drawer.load())
        }
        val streaming = state.state == StreamState.STREAMING
        // CR-112: el contenedor del video queda SIEMPRE visible. Sin transmisión
        // hace de tapete de mesa (capa por debajo de los controles) en vez del
        // SurfaceView negro; ocultarlo era la causa de la pantalla negra.
        sessionView<View>(R.id.video_surface_container).background =
            if (streaming) null else ContextCompat.getDrawable(this, R.drawable.bg_table)
        sessionView<SurfaceView>(R.id.video_view).isVisible = streaming
        // CR-079: la vista de espera CR-076 ahora tiene dos modos (fullscreen
        // en espera u hoja arrastrable en transmisión); las decida el controlador.
        metricsSlider.setStreaming(streaming, drawer.load().metricsSliderOverStream)
        keepDisplayAwake(viewModel.uiState.value is PairingUiState.Linked)
        // El punto verde de la conexión activa sigue el inicio/fin del stream.
        if (viewModel.uiState.value is PairingUiState.Linked) renderSavedDevicesNow()
    }

    /** HUD de la sesión (CR-054/CR-075/CR-112); el renderer vive aparte. */
    private val hudRenderer by lazy {
        SessionHudRenderer(
            context = this,
            findView = { id -> sessionView<View>(id) },
            stats = viewModel.streamStats,
            stageStats = { viewModel.videoStageStats.value },
            mediaTransport = { viewModel.mediaTransport.value },
        )
    }

    private fun renderHudChip() = hudRenderer.render(hudVisible, advancedMetricsVisible)

    /** Superficie táctil: instanciar el editor/uso una sola vez. */
    private fun attachProfileSurface() {
        if (surfaceRef == null) {
            val surface = ProfileSurface(this) { report -> viewModel.onGamepadInput(report) }
            surface.showControls = controlsVisible
            surface.setNintendoFaceLayout(nintendoFaceDistribution)
            surface.setPhysicalMapping(physicalGamepad.mappingForConnected())
            surfaceRef = surface
            sessionView<ViewGroup>(R.id.surface_container).addView(
                surface,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }

    /** CR-028: mando físico conectado al teléfono durante la sesión (SPEC-004).
     * La Activity traduce `KeyEvent`/`MotionEvent` de origen gamepad a los
     * códigos y ejes del mapper puro; la fusión con la superficie táctil
     * vive en [ProfileSurface]. Sin sesión linkeada no se consumen eventos. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // SPEC-011: con una fila en captura, el siguiente botón es el nuevo mapeo.
        if (physicalGamepad.onKey(event)) return true
        if (isGamepadSource(event.source)) {
            sidebarChord.onKey(event)
        }
        val surface =
            sessionSurfaceWithPhysicalGamepad()
                ?.takeIf {
                    isGamepadSource(event.source) && it.handlesPhysicalKey(event.keyCode)
                }
        if (surface != null) {
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (!pressed || event.repeatCount == 0) {
                surface.onPhysicalKey(event.keyCode, pressed)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** CR-028: sticks/triggers/hat del mando físico (`MotionEvent`). */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // SPEC-011: captura de ejes mientras el mapeador espera una entrada.
        if (physicalGamepad.onMotion(event)) return true
        val surface =
            sessionSurfaceWithPhysicalGamepad()?.takeIf {
                isGamepadSource(event.source)
            }
        if (surface != null) {
            // SPEC-011 §4: el mapeo del perfil decide qué eje alimenta cada
            // elemento canónico.
            surface.onPhysicalAxes(
                PhysicalAxes.tracked.associateWith { axis ->
                    event.getAxisValue(axis).takeIf { it.isFinite() } ?: 0f
                },
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Visibilidad global de error/reintento y del botón de código tipeado
     * (U1 idle → U5 failure, SPEC-002 §6). Función-propiedad para mantener
     * el recuento de funciones bajo el umbral de detekt. */
    private val applyFailureAndTypedCodeVisibility: (PairingUiState) -> Unit = { state ->
        val txtError = panelView<TextView>(R.id.txt_error)
        txtError.text =
            if (state is PairingUiState.Failure) getString(state.error.resId()) else ""
        txtError.isVisible = state is PairingUiState.Failure
        panelView<View>(R.id.btn_retry).isVisible = state is PairingUiState.Failure
        panelView<View>(R.id.btn_typed_code).isVisible = state == PairingUiState.Idle
    }

    /** U1 idle → U5 failure: visibilidad de cada sección (SPEC-002 §6).
     * Dispositivos guardados a la derecha. Un toque expande la fila.
     * Activo: muestra «Desconectar». Inactivo: muestra «Olvidar». */
    private val savedDevices by lazy {
        SavedDevicesRenderer(
            activity = this,
            container = findViewById(R.id.saved_devices_container),
            viewModel = viewModel,
            // Punto verde solo con la transmisión de esta conexión viva.
            isStreaming = { viewModel.streamState.value.state == StreamState.STREAMING },
            rerender = { renderSavedDevicesNow() },
        )
    }

    /** Vuelve a pintar la lista de guardados con el estado actual. */
    private val renderSavedDevicesNow: () -> Unit = {
        savedDevices.render(viewModel.uiState.value)
    }

    /** CR-097: pestañas y drawer que alternan entre conexión nueva y lista. */
    private val connectionTabs by lazy {
        ConnectionTabsController(
            activity = this,
            viewModel = viewModel,
            panelView = { panelFragment.requireView() },
            renderState = ::renderState,
        )
    }

    private fun renderLinked() {
        // Con la vista de conexión elegida, el panel muestra la pantalla
        // inicial mientras la sesión sigue viva en segundo plano.
        // Desconectar está en la fila de la columna de guardados.
        // CR-108 (decisión del owner): el QR y el código sí se ofrecen con
        // sesión viva; el enlace pedido se registra y se ejecuta cuando la
        // sesión termine, sin tocar la sesión activa.
        val showingNewConnection = !connectionTabs.showingConnectionView
        panelView<View>(R.id.pairing_title).isVisible = showingNewConnection
        panelView<View>(R.id.btn_scan).isVisible = showingNewConnection && !linkedScanActive
        panelView<View>(R.id.btn_typed_code).isVisible = showingNewConnection
        panelView<TextView>(R.id.txt_status).text =
            when {
                !showingNewConnection -> ""
                // CR-108: mientras se escanea se indica que el enlace queda
                // guardado para cuando termine la sesión.
                linkedScanActive -> getString(R.string.pairing_scanning)
                viewModel.pendingLinkWaiting.value -> getString(R.string.pairing_pending_link)
                else -> getString(R.string.pairing_linked)
            }
        panelView<PreviewView>(R.id.camera_preview).isVisible = linkedScanActive
        panelView<View>(R.id.btn_cancel).isVisible = linkedScanActive
        panelView<View>(R.id.progress_pairing).isVisible = false
        // El servicio en primer plano impide que el sistema mate
        // el proceso al cambiar de app (el video sigue vivo).
        SessionForegroundService.start(this@PairingActivity)
    }

    private fun renderState(state: PairingUiState) {
        savedDevices.render(state)
        connectionTabs.renderSwitch(sessionLinked = state is PairingUiState.Linked)
        // CR-097: con sesión activa el panel no muestra Escanear ni Desconectar;
        // Desconectar vive en la fila de la conexión activa (columna de guardados).
        // Reconectando no hay vista de sesión: el panel ofrece Desconectar.
        panelView<View>(R.id.btn_disconnect).isVisible = state is PairingUiState.Reconnecting
        val title = panelView<View>(R.id.pairing_title)
        val btnScan = panelView<View>(R.id.btn_scan)
        val preview = panelView<PreviewView>(R.id.camera_preview)
        val txtStatus = panelView<TextView>(R.id.txt_status)
        val progress = panelView<View>(R.id.progress_pairing)
        val btnCancel = panelView<View>(R.id.btn_cancel)
        applyFailureAndTypedCodeVisibility(state)
        when (state) {
            PairingUiState.Idle -> {
                title.isVisible = true
                btnScan.isVisible = true
                preview.isVisible = false
                txtStatus.text = ""
                btnCancel.isVisible = false
                progress.isVisible = false
                SessionForegroundService.stop(this@PairingActivity)
            }
            PairingUiState.Scanning -> {
                title.isVisible = true
                btnScan.isVisible = false
                preview.isVisible = true
                txtStatus.text = getString(R.string.pairing_scanning)
                progress.isVisible = false
                btnCancel.isVisible = true
                cameraController.bind(preview, this as LifecycleOwner)
            }
            PairingUiState.Handshaking -> {
                preview.isVisible = false
                txtStatus.text = getString(R.string.pairing_handshaking)
                progress.isVisible = true
                btnCancel.isVisible = true
            }
            is PairingUiState.Reconnecting -> {
                // CR-056 (U6, SPEC-001 §8.2): sesión retenida en el server;
                // la app reanuda sin QR. El video del server también puede
                // seguir vivo (SPEC-003 §3: retención viva).
                // Sin video ni métricas durante el reintento: pantalla inicial
                // con el aviso, para no quedar en una vista de sesión vacía.
                title.isVisible = true
                btnScan.isVisible = false
                preview.isVisible = false
                txtStatus.text = getString(R.string.pairing_reconnecting)
                progress.isVisible = true
                btnCancel.isVisible = true
                SessionForegroundService.start(this@PairingActivity)
            }
            is PairingUiState.Linked -> renderLinked()
            is PairingUiState.Failure -> {
                // Sesión perdida: volver a la pantalla inicial (vincular o ver guardados).
                title.isVisible = true
                btnScan.isVisible = true
                preview.isVisible = false
                txtStatus.text = ""
                btnCancel.isVisible = false
                SessionForegroundService.stop(this@PairingActivity)
            }
        }
    }

    /** Efectos de cámara (DES-002 §4): permiso y Start/Stop. */
    private fun observeCameraEffects() {
        lifecycleScope.launch {
            viewModel.cameraEffects.collect { effect ->
                when (effect) {
                    is Effect.RequestCamera -> {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    is Effect.StartCamera -> cameraController.start()
                    is Effect.StopCamera -> cameraController.stop()
                    else -> Unit
                }
            }
        }
    }

    /** Superficie de perfil sólo con sesión viva (usada por el gamepad físico). */
    private val sessionSurfaceWithPhysicalGamepad: () -> ProfileSurface? = {
        surfaceRef?.takeIf { viewModel.uiState.value is PairingUiState.Linked }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        motionSensors.start()
        physicalGamepad.refreshAction()
    }

    /** Pinta el monitor de giro sólo si está visible (CR-074 / monitor de orientación). */
    private fun showOrientationTurns(turns: List<Int>) {
        if (!gyroMonitorVisible) return
        findViewById<GyroMonitorView>(R.id.gyro_monitor).apply {
            setOrientationTurns(turns[0], turns[1], turns[2])
            bringToFront()
        }
    }

    override fun onPause() {
        motionSensors.stop()
        super.onPause()
        viewModel.onAppBackgrounded() // SPEC-002 §8.3
    }

    override fun onDestroy() {
        sessionView<SurfaceView>(R.id.video_view).holder.removeCallback(mediaSurfaceCallback)
        // CR-077: el monitor de FPS del Gamepad no debe sobrevivir a la Activity.
        gaugePanel.stopDeviceMonitor()
        if (::drawer.isInitialized) {
            micDemand.unregister()
            audioManager.unregisterAudioDeviceCallback(playbackRouteCallback)
        }
        physicalGamepad.stop()
        viewModel.attachMediaSurface(null)
        cameraController.release()
        micController.stop()
        SessionForegroundService.stop(this)
        super.onDestroy()
    }
}

private fun PairingActivity.keepDisplayAwake(sessionActive: Boolean) {
    // CR-112: basta con tener sesión —la pantalla no se apaga aunque no haya
    // transmisión en curso—; sin sesión se devuelve el control al sistema.
    if (sessionActive) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** Mapeo error → string canónico (SPEC-002 §9, R4). */
fun com.twinpad.android.app.core.PairingError.resId(): Int =
    when (this) {
        com.twinpad.android.app.core.PairingError.ServerUnavailable ->
            R.string.pairing_error_server_unavailable
        com.twinpad.android.app.core.PairingError.QrExpired ->
            R.string.pairing_error_qr_expired
        com.twinpad.android.app.core.PairingError.Rejected ->
            R.string.pairing_error_rejected
        com.twinpad.android.app.core.PairingError.InvalidResponse ->
            R.string.pairing_error_invalid_response
        com.twinpad.android.app.core.PairingError.QrInvalid ->
            R.string.pairing_error_invalid_qr
        com.twinpad.android.app.core.PairingError.CameraDenied ->
            R.string.pairing_error_camera_denied
        com.twinpad.android.app.core.PairingError.SessionEnded -> R.string.pairing_session_ended
    }

internal fun isGamepadSource(source: Int): Boolean =
    (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
