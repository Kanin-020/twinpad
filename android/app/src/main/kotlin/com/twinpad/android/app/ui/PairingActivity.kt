package com.twinpad.android.app.ui

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
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
import com.twinpad.android.app.pairing.PairingViewModel
import com.twinpad.android.app.session.SessionForegroundService
import com.twinpad.android.app.stream.StreamState
import com.twinpad.android.app.wire.ProtocolConstants
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Delay del tick del HUD (ms). */
private const val HUD_TICK_MS: Long = 1_000L

/** Conversión kbps → Mbps. */
private const val KBPS_PER_MBPS: Int = 1_000

/**
 * Pantalla de Pairing (SPEC-002 §6) sobre Views clásicas: layouts XML +
 * drawables XML editables en Android Studio (pedido del owner: mantener la
 * UI fuera del código). Observa el mismo [PairingViewModel] (la lógica no
 * cambió: DES-002 §4). El drawer lateral alterna el HUD y los controles.
 */
class PairingActivity : AppCompatActivity() {
    private lateinit var viewModel: PairingViewModel
    private lateinit var cameraController: PairingCameraController
    private lateinit var drawer: SettingsDrawerController
    private var surfaceRef: ProfileSurface? = null
    private var hudVisible = true
    private var controlsVisible = true
    private var gyroMonitorVisible = false
    private var sessionConfigSent = false
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
                viewModel.attachMediaSurface(holder.surface)
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

    /** Aplica el modo de escalado CR-025: letterbox en `contain`, relleno en `expand`. */
    private fun applyVideoScale(settings: AndroidSettings) {
        val container = findViewById<ViewGroup>(R.id.video_surface_container)
        val video = findViewById<SurfaceView>(R.id.video_view)
        container.post {
            val rect =
                videoContentRect(
                    settings.scaleMode,
                    container.width.toFloat(),
                    container.height.toFloat(),
                    settings.width,
                    settings.height,
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
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                motionController.updateAcceleration(event.values, event.timestamp)
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }
    private val gyroscopeListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                motionController.updateGyroscope(event.values, event.timestamp)
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }
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

    /** Efectos del drawer que exceden sus vistas: hardware y estado propio. */
    private val drawerHost =
        object : SettingsDrawerHost {
            override fun applyVideoScale(settings: AndroidSettings) {
                this@PairingActivity.applyVideoScale(settings)
            }

            override fun onStreamSettingsChanged(settings: AndroidSettings) {
                viewModel.onStreamSettingsChanged(settings)
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

            override fun onGyroMonitorToggled(visible: Boolean) {
                gyroMonitorVisible = visible
                findViewById<GyroMonitorView>(R.id.gyro_monitor).isVisible = visible
                findViewById<GyroMonitorView>(R.id.gyro_monitor).bringToFront()
            }

            override fun onControlsToggled(visible: Boolean) {
                controlsVisible = visible
                surfaceRef?.showControls = visible
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContentView(R.layout.activity_pairing)
        viewModel = ViewModelProvider(this)[PairingViewModel::class.java]
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        cameraController =
            PairingCameraController(this) { raw ->
                viewModel.onEvent(PairingEvent.QrScanned(raw))
            }
        drawer = SettingsDrawerController(this, drawerHost)
        wireViews()
        findViewById<SurfaceView>(R.id.video_view).holder.addCallback(mediaSurfaceCallback)
        findViewById<View>(R.id.video_surface_container).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyVideoScale(drawer.load())
        }
        drawer.applyPersistedSettings()
        observeCameraEffects()
        observeStates()
    }

    /** Cablea cada View a su evento del ViewModel. */
    private fun wireViews() {
        findViewById<Button>(R.id.btn_scan).setOnClickListener {
            viewModel.onEvent(PairingEvent.ScanRequested)
        }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            viewModel.onEvent(PairingEvent.Cancelled)
        }
        findViewById<Button>(R.id.btn_retry).setOnClickListener {
            viewModel.onEvent(PairingEvent.Retry)
        }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).openDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btn_close_drawer).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).closeDrawer(GravityCompat.START)
        }
    }

    /** Observa la sesión completa (video por frame, HUD por tick). */
    private fun observeStates() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                renderState(state)
                if (state is PairingUiState.Linked && !sessionConfigSent) {
                    viewModel.onStreamSettingsChanged(drawer.load())
                    sessionConfigSent = true
                } else if (state !is PairingUiState.Linked) {
                    sessionConfigSent = false
                }
            }
        }
        lifecycleScope.launch {
            viewModel.streamState.collectLatest { s ->
                // SPEC-003 §5: STREAMING = segunda pantalla activa (TX visible);
                // INTERRUPTED = reconectando; el resto (INACTIVE/STOP) = sin
                // pantalla. Antes STREAMING caía en el else y la app mostraba
                // "Sin segunda pantalla activa" CON imagen viva.
                findViewById<TextView>(R.id.txt_session_message).text =
                    when (s.state) {
                        StreamState.STREAMING ->
                            getString(R.string.second_screen_active)
                        StreamState.INTERRUPTED ->
                            getString(R.string.second_screen_reconnecting)
                        else -> getString(R.string.second_screen_inactive)
                    }
                keepDisplayAwake(s.state)
            }
        }
        lifecycleScope.launch {
            viewModel.availableGames.collectLatest { games ->
                findViewById<TextView>(R.id.txt_games).text = gamesText(games)
            }
        }
        lifecycleScope.launch {
            viewModel.selectedGameId.collectLatest { gameId ->
                surfaceRef?.loadProfile(gameId.orEmpty())
            }
        }
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
            }
        }
        drawer.bind()
        attachProfileSurface()
    }

    private fun gamesText(games: List<com.twinpad.android.app.wire.GameInfo>): String =
        when {
            games.isEmpty() -> ""
            games.size == 1 -> getString(R.string.game_compatible_one, games[0].gameName)
            else -> getString(R.string.game_compatible_many, games.joinToString { it.gameName })
        }

    private fun renderHudChip() {
        val chip = findViewById<TextView>(R.id.hud_chip)
        if (!hudVisible) {
            chip.isVisible = false
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
    }

    /** Superficie táctil: instanciar el editor/uso una sola vez. */
    private fun attachProfileSurface() {
        if (surfaceRef == null) {
            val surface = ProfileSurface(this) { report -> viewModel.onGamepadInput(report) }
            surface.showControls = controlsVisible
            surfaceRef = surface
            findViewById<ViewGroup>(R.id.surface_container).addView(
                surface,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }

    /** U1 idle → U5 failure: visibilidad de cada sección (SPEC-002 §6). */
    private fun renderState(state: PairingUiState) {
        val session = findViewById<View>(R.id.session_view)
        val title = findViewById<View>(R.id.pairing_title)
        val btnScan = findViewById<View>(R.id.btn_scan)
        val preview = findViewById<PreviewView>(R.id.camera_preview)
        val txtStatus = findViewById<TextView>(R.id.txt_status)
        val btnCancel = findViewById<View>(R.id.btn_cancel)
        val btnRetry = findViewById<View>(R.id.btn_retry)
        val txtError = findViewById<TextView>(R.id.txt_error)
        txtError.text =
            if (state is PairingUiState.Failure) getString(state.error.resId()) else ""
        btnRetry.isVisible = state is PairingUiState.Failure
        txtError.isVisible = state is PairingUiState.Failure
        when (state) {
            PairingUiState.Idle -> {
                session.isVisible = false
                title.isVisible = true
                btnScan.isVisible = true
                preview.isVisible = false
                txtStatus.text = ""
                btnCancel.isVisible = false
                SessionForegroundService.stop(this@PairingActivity)
            }
            PairingUiState.Scanning -> {
                session.isVisible = false
                title.isVisible = true
                btnScan.isVisible = false
                preview.isVisible = true
                txtStatus.text = getString(R.string.pairing_scanning)
                btnCancel.isVisible = true
                cameraController.bind(preview, this as LifecycleOwner)
            }
            PairingUiState.Handshaking -> {
                session.isVisible = false
                preview.isVisible = false
                txtStatus.text = getString(R.string.pairing_handshaking)
                btnCancel.isVisible = true
            }
            is PairingUiState.Linked -> {
                session.isVisible = true
                preview.isVisible = false
                btnCancel.isVisible = false
                // El servicio en primer plano impide que el sistema mate
                // el proceso al cambiar de app (el video sigue vivo).
                SessionForegroundService.start(this@PairingActivity)
            }
            is PairingUiState.Failure -> {
                session.isVisible = false
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
                    is Effect.RequestCamera ->
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    is Effect.StartCamera -> cameraController.start()
                    is Effect.StopCamera -> cameraController.stop()
                    else -> Unit
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        motionController.reset()
        rotationVector?.let {
            sensorManager.registerListener(
                orientationListener,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                ProtocolConstants.SENSOR_REPORT_INTERVAL_US,
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(
                accelerationListener,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                ProtocolConstants.SENSOR_REPORT_INTERVAL_US,
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(
                gyroscopeListener,
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
        findViewById<SurfaceView>(R.id.video_view).holder.removeCallback(mediaSurfaceCallback)
        viewModel.attachMediaSurface(null)
        cameraController.release()
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
        com.twinpad.android.app.core.PairingError.SessionEnded ->
            R.string.pairing_session_ended
    }
