package com.twinpad.android.app.ui

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.twinpad.android.app.R
import com.twinpad.android.app.core.Effect
import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.hid.PhysicalMotion
import com.twinpad.android.app.pairing.PairingViewModel
import com.twinpad.android.app.session.SessionForegroundService
import com.twinpad.android.app.stream.StreamState
import com.twinpad.android.app.wire.ProtocolConstants
import com.twinpad.android.app.wire.VideoStageStatsCodec
import com.twinpad.android.app.wire.VideoStageStatsMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Delay del tick del HUD (ms). */
private const val HUD_TICK_MS: Long = 1_000L

/** Conversión kbps → Mbps. */
private const val KBPS_PER_MBPS: Int = 1_000

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
    private var rotationVector: Sensor? = null
    private val orientationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

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

    private val motionController =
        ProfileGyroController { sample ->
            surfaceRef?.updateMotion(sample)
        }
    private val accelerationListener =
        sensorListener { values, timestamp -> motionController.updateAcceleration(values, timestamp) }
    private val gyroscopeListener =
        sensorListener { values, timestamp -> motionController.updateGyroscope(values, timestamp) }
    private val orientationListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(orientationMatrix, event.values)
                remapOrientationMatrix(orientationMatrix, display.rotation)
                SensorManager.getOrientation(orientationMatrix, orientationAngles)
                if (gyroMonitorVisible) {
                    val orientationTurns = GyroOrientationMapper.toScreenTurns(orientationAngles)
                    findViewById<GyroMonitorView>(R.id.gyro_monitor).apply {
                        setOrientationTurns(orientationTurns[0], orientationTurns[1], orientationTurns[2])
                        bringToFront()
                    }
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }

    /** CR-035: permiso del micrófono bajo demanda; al concederlo se reevalúa
     * el estado combinado de la sesión (juego «cemu» + `mic_request`). */
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) applyMicDemand()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_pairing)
        // CR-097 paso 1: el panel de conexión vive en PairingFragment.
        panelFragment = PairingFragment(onPanelReady = ::onPanelReady)
        sessionFragment = SessionFragment(onSessionReady = ::onSessionReady)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.pairing_fragment_container, panelFragment)
                .replace(R.id.saved_devices_container, SavedDevicesFragment())
                .replace(R.id.session_container, sessionFragment)
                .commitNow()
        }
        viewModel = ViewModelProvider(this, PairingViewModel.factory(application))[PairingViewModel::class.java]
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
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
     * CR-097: el panel de conexión existe desde onViewCreated del fragmento.
     * Sólo entonces se cablean sus botones y se empieza a observar el estado,
     * porque renderState usa las vistas del panel.
     */
    private fun onPanelReady() {
        wireViews()
        observeStates()
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
        sessionView<ImageButton>(R.id.btn_settings).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).openDrawer(GravityCompat.START)
        }
        observeMicDemand()
        observeKeyboardDemand()
        drawer.bind()
        attachProfileSurface()
        // Se suscribe aquí: StateFlow emite el valor actual, y la vista ya existe.
        lifecycleScope.launch {
            viewModel.streamState.collectLatest { state ->
                applyStreamState(state)
            }
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
            viewModel.onEvent(PairingEvent.ScanRequested)
        }
        // CR-060 (SPEC-002 §8.4): el payload textual que la PC ya imprime
        // junto al QR se escribe/pega aquí; misma ruta que el QR sin cámara.
        panelView<Button>(R.id.btn_typed_code).setOnClickListener {
            showTypedCodeDialog()
        }
        panelView<Button>(R.id.btn_cancel).setOnClickListener {
            viewModel.onEvent(PairingEvent.Cancelled)
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
        findViewById<Button>(R.id.btn_drawer_connection).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).closeDrawer(GravityCompat.START)
            // CR-097: sólo cambia de vista; la sesión sigue viva en segundo plano.
            connectionTabs.toggleFromDrawer()
        }
        findViewById<Button>(R.id.btn_tab_new).setOnClickListener {
            connectionTabs.show(connectionView = false)
        }
        findViewById<Button>(R.id.btn_tab_list).setOnClickListener {
            connectionTabs.show(connectionView = true)
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

    /** CR-035 + CR-090: captura solo con el juego seleccionado, la demanda
     * `mic_request` vigente Y el interruptor «Enviar micrófono al GamePad»
     * activado (preferencia persistida que lee el drawer). Si falta el
     * permiso RECORD_AUDIO se solicita y el lanzador reevalúa la demanda al
     * concederlo; sin gate completo se para la captura. Función-propiedad
     * para mantener el recuento de funciones bajo el umbral de detekt. */
    private val applyMicDemand: () -> Unit = {
        val demandActive =
            viewModel.selectedGameId.value == "cemu" &&
                viewModel.micRequested.value &&
                drawer.load().microphoneStream
        when {
            !demandActive -> micController.stop()
            micController.hasPermission() -> micController.start()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Observa la sesión completa (video por frame, HUD por tick). */
    private fun observeStates() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                applyLinkedState(state)
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
        if (state is PairingUiState.Linked && !sessionConfigSent) {
            viewModel.onStreamSettingsChanged(drawer.load())
            sessionConfigSent = true
        } else if (state !is PairingUiState.Linked) {
            sessionConfigSent = false
        }
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
        sessionView<View>(R.id.drc_content).isVisible = streaming
        // CR-079: la vista de espera CR-076 ahora tiene dos modos (fullscreen
        // en espera u hoja arrastrable en transmisión); las decida el controlador.
        metricsSlider.setStreaming(streaming, drawer.load().metricsSliderOverStream)
        keepDisplayAwake(state.state)
    }

    private fun renderHudChip() {
        val chip = sessionView<TextView>(R.id.hud_chip)
        val stageLine = sessionView<TextView>(R.id.hud_stage)
        if (!hudVisible) {
            chip.isVisible = false
            stageLine.isVisible = false
            return
        }
        val stats = viewModel.streamStats
        stats.refresh()
        chip.text =
            getString(
                R.string.hud_chip_text,
                "%.0f fps".format(stats.fps),
                "%.0f fps".format(stats.receiveFps),
                "%.1f".format(stats.kbps / KBPS_PER_MBPS),
            )
        chip.isVisible = true
        // CR-054: las métricas por etapa viven en un bloque separado
        // («Métricas avanzadas») con su propio interruptor del drawer.
        val metrics = viewModel.videoStageStats.value
        val formatted = metrics?.let(VideoStageStatsCodec::format)
        if (!advancedMetricsVisible || formatted == null) {
            stageLine.isVisible = false
        } else {
            stageLine.text =
                getString(R.string.hud_stage_title) + "\n" + formatted
            stageLine.isVisible = true
        }
        renderHudStageDetails(metrics)
    }

    /** CR-075: segunda línea del HUD («Métricas avanzadas») con SOLO lo
     * conocido: backend del encoder (hw), transporte media (UDP/TCP) y las
     * resoluciones de entrada/salida que reporta el Plugin en la ventana.
     * Función-propiedad para mantener el recuento de funciones bajo el
     * umbral de detekt. */
    private val renderHudStageDetails = { metrics: VideoStageStatsMessage? ->
        val details = sessionView<TextView>(R.id.hud_stage_details)
        if (!advancedMetricsVisible) {
            details.isVisible = false
        } else {
            val transport = viewModel.mediaTransport.value?.let { if (it) "UDP" else "TCP" }
            val detailsText = metrics?.let { VideoStageStatsCodec.formatDetails(it, transport) }
            if (detailsText == null) {
                details.isVisible = false
            } else {
                details.text = detailsText
                details.isVisible = true
            }
        }
    }

    /** Superficie táctil: instanciar el editor/uso una sola vez. */
    private fun attachProfileSurface() {
        if (surfaceRef == null) {
            val surface = ProfileSurface(this) { report -> viewModel.onGamepadInput(report) }
            surface.showControls = controlsVisible
            surface.setNintendoFaceLayout(nintendoFaceDistribution)
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
        val surface =
            sessionSurfaceWithPhysicalGamepad()?.takeIf {
                isGamepadSource(event.source)
            }
        if (surface != null) {
            surface.onPhysicalMotion(
                PhysicalMotion(
                    leftX = event.getAxisValue(MotionEvent.AXIS_X),
                    leftY = event.getAxisValue(MotionEvent.AXIS_Y),
                    rightX = event.getAxisValue(MotionEvent.AXIS_Z),
                    rightY = event.getAxisValue(MotionEvent.AXIS_RZ),
                    triggerL =
                        maxOf(
                            event.getAxisValue(MotionEvent.AXIS_BRAKE),
                            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                        ),
                    triggerR =
                        maxOf(
                            event.getAxisValue(MotionEvent.AXIS_GAS),
                            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                        ),
                    hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt(),
                    hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt(),
                ),
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

    private fun renderState(state: PairingUiState) {
        savedDevices.render(state)
        connectionTabs.renderSwitch(state, sessionLinked = state is PairingUiState.Linked)
        // CR-097: con sesión activa el panel no muestra Escanear ni Desconectar;
        // Desconectar vive en la fila de la conexión activa (columna de guardados).
        // Reconectando no hay vista de sesión: el panel ofrece Desconectar.
        panelView<View>(R.id.btn_disconnect).isVisible = state is PairingUiState.Reconnecting
        val title = panelView<View>(R.id.pairing_title)
        val btnScan = panelView<View>(R.id.btn_scan)
        val preview = panelView<PreviewView>(R.id.camera_preview)
        val txtStatus = panelView<TextView>(R.id.txt_status)
        val btnCancel = panelView<View>(R.id.btn_cancel)
        applyFailureAndTypedCodeVisibility(state)
        when (state) {
            PairingUiState.Idle -> {
                title.isVisible = true
                btnScan.isVisible = true
                preview.isVisible = false
                txtStatus.text = ""
                btnCancel.isVisible = false
                SessionForegroundService.stop(this@PairingActivity)
            }
            PairingUiState.Scanning -> {
                title.isVisible = true
                btnScan.isVisible = false
                preview.isVisible = true
                txtStatus.text = getString(R.string.pairing_scanning)
                btnCancel.isVisible = true
                cameraController.bind(preview, this as LifecycleOwner)
            }
            PairingUiState.Handshaking -> {
                preview.isVisible = false
                txtStatus.text = getString(R.string.pairing_handshaking)
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
                btnCancel.isVisible = true
                SessionForegroundService.start(this@PairingActivity)
            }
            is PairingUiState.Linked -> {
                // Con la vista de conexión elegida, el panel muestra la pantalla
                // inicial mientras la sesión sigue viva en segundo plano.
                // CR-097: con conexión activa no se ofrece Escanear; Desconectar
                // está en la fila de la columna de guardados.
                title.isVisible = connectionTabs.showingConnectionView
                btnScan.isVisible = false
                txtStatus.text = if (connectionTabs.showingConnectionView) getString(R.string.pairing_linked) else ""
                preview.isVisible = false
                btnCancel.isVisible = false
                // El servicio en primer plano impide que el sistema mate
                // el proceso al cambiar de app (el video sigue vivo).
                SessionForegroundService.start(this@PairingActivity)
            }
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
        motionController.reset()
        registerSensor(rotationVector, orientationListener)
        registerSensor(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), accelerationListener)
        registerSensor(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), gyroscopeListener)
    }

    /** Registra el listener solo si el sensor existe en el dispositivo. */
    private fun registerSensor(
        sensor: Sensor?,
        listener: SensorEventListener,
    ) {
        sensor?.let {
            sensorManager.registerListener(
                listener,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                ProtocolConstants.SENSOR_REPORT_INTERVAL_US,
            )
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(accelerationListener)
        sensorManager.unregisterListener(gyroscopeListener)
        sensorManager.unregisterListener(orientationListener)
        motionController.reset()
        super.onPause()
        viewModel.onAppBackgrounded() // SPEC-002 §8.3
    }

    override fun onDestroy() {
        sessionView<SurfaceView>(R.id.video_view).holder.removeCallback(mediaSurfaceCallback)
        // CR-077: el monitor de FPS del Gamepad no debe sobrevivir a la Activity.
        gaugePanel.stopDeviceMonitor()
        viewModel.attachMediaSurface(null)
        cameraController.release()
        micController.stop()
        SessionForegroundService.stop(this)
        super.onDestroy()
    }
}

private fun PairingActivity.keepDisplayAwake(state: StreamState) {
    if (state == StreamState.STREAMING || state == StreamState.INTERRUPTED) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** Listener de sensor que solo reenvía valores y marca de tiempo; la precisión no se usa. */
private fun sensorListener(onValues: (FloatArray, Long) -> Unit): SensorEventListener =
    object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onValues(event.values, event.timestamp)
        }

        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int,
        ) = Unit
    }

/** Reorienta la referencia de ejes del sensor para que siga las coordenadas visibles de la pantalla. */
internal fun remapOrientationMatrix(
    matrix: FloatArray,
    displayRotation: Int,
) {
    val (xAxis, yAxis) =
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    val remapped = FloatArray(matrix.size)
    if (SensorManager.remapCoordinateSystem(matrix, xAxis, yAxis, remapped)) {
        remapped.copyInto(matrix)
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

private fun isGamepadSource(source: Int): Boolean =
    source and InputDevice.SOURCE_GAMEPAD != 0 || source and InputDevice.SOURCE_JOYSTICK != 0
