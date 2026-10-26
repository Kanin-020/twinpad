package com.twinpad.android.app.ui

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.SeekBar
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
import com.google.android.material.switchmaterial.SwitchMaterial
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

/** Resoluciones seleccionables (16:9, CR-001). */
private const val RES_W_960 = 960
private const val RES_H_540 = 540
private const val RES_W_1280 = 1280
private const val RES_H_720 = 720
private const val RES_W_1920 = 1920
private const val RES_H_1080 = 1080

/** fps seleccionables. */
private const val FPS_30 = 30
private const val FPS_60 = 60
private const val AUDIO_VOLUME_PERCENT_MAX = 100
private const val JOG_WHEEL_PERCENT_SCALE = 100

/**
 * Pantalla de Pairing (SPEC-002 §6) sobre Views clásicas: layouts XML +
 * drawables XML editables en Android Studio (pedido del owner: mantener la
 * UI fuera del código). Observa el mismo [PairingViewModel] (la lógica no
 * cambió: DES-002 §4). El drawer lateral alterna el HUD y los controles.
 */
class PairingActivity : AppCompatActivity() {
    private lateinit var viewModel: PairingViewModel
    private lateinit var cameraController: PairingCameraController
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
        wireViews()
        findViewById<SurfaceView>(R.id.video_view).holder.addCallback(mediaSurfaceCallback)
        applyPersistedSettings()
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
        findViewById<TextView>(R.id.editor_entry).setOnClickListener {
            surfaceRef?.toggleEditing()
        }
        findViewById<Button>(R.id.editor_add_button).setOnClickListener { surfaceRef?.addControl("button") }
        findViewById<Button>(R.id.editor_add_stick).setOnClickListener { surfaceRef?.addControl("stick") }
        findViewById<Button>(R.id.editor_add_dpad).setOnClickListener { surfaceRef?.addControl("dpad") }
        findViewById<Button>(R.id.editor_delete).setOnClickListener { surfaceRef?.deleteSelectedControl() }
        findViewById<Button>(R.id.editor_assign).setOnClickListener {
            val assignments = surfaceRef?.availableAssignments().orEmpty()
            if (assignments.isNotEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.editor_assign)
                    .setItems(assignments.toTypedArray()) { _, index ->
                        surfaceRef?.assignSelected(assignments[index])
                    }
                    .show()
            }
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
                    viewModel.onStreamSettingsChanged(AndroidSettingsStore(this@PairingActivity).load())
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
        wireDrawerControls()
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

    /** Drawer de ajustes: resolución/fps/calidad/HUD/controles → ViewModel. */
    private fun applyPersistedSettings() {
        val settings = AndroidSettingsStore(this).load()
        viewModel.setAudioVolume(settings.audioVolumePercent / AUDIO_VOLUME_PERCENT_MAX.toFloat())
        resolutionButtons().forEach { (button, dims) ->
            if (dims.first == settings.width && dims.second == settings.height) button.isChecked = true
        }
        if (!hasConfiguredResolution(settings.width, settings.height)) {
            findViewById<RadioButton>(R.id.resolution_1280x720).isChecked = true
        }
        findViewById<RadioButton>(if (settings.fps == FPS_30) R.id.fps_30 else R.id.fps_60).isChecked = true
        findViewById<SeekBar>(R.id.jpeg_quality_seek).progress = settings.jpegQuality
        findViewById<SeekBar>(R.id.audio_volume_seek).progress = settings.audioVolumePercent
        hudVisible = settings.showHud
        controlsVisible = settings.showControls
        findViewById<SwitchMaterial>(R.id.switch_show_fps).isChecked = settings.showHud
        findViewById<SwitchMaterial>(R.id.switch_show_controls).isChecked = settings.showControls
        gyroMonitorVisible = settings.showGyroMonitor
        findViewById<SwitchMaterial>(R.id.switch_show_gyro_monitor).isChecked = settings.showGyroMonitor
        findViewById<GyroMonitorView>(R.id.gyro_monitor).isVisible = settings.showGyroMonitor
        findViewById<GyroMonitorView>(R.id.gyro_monitor).bringToFront()
        findViewById<ViewGroup>(R.id.surface_container).isVisible = settings.showControls
    }

    private fun hasConfiguredResolution(
        width: Int,
        height: Int,
    ): Boolean = resolutionButtons().values.any { dims -> dims.first == width && dims.second == height }

    private fun saveSettings(transform: (AndroidSettings) -> AndroidSettings) {
        val store = AndroidSettingsStore(this)
        val updated = transform(store.load())
        store.save(updated)
        viewModel.onStreamSettingsChanged(updated)
    }

    private fun wireDrawerControls() {
        resolutionButtons().forEach { (button, dims) ->
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) saveSettings { it.copy(width = dims.first, height = dims.second, fps = currentFps()) }
            }
        }
        fpsButtons().forEach { (button, fps) ->
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val (width, height) = currentResolution()
                    saveSettings { it.copy(width = width, height = height, fps = fps) }
                }
            }
        }
        findViewById<SeekBar>(R.id.jpeg_quality_seek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) saveSettings { it.copy(jpegQuality = value) }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
        val audioVolume = findViewById<SeekBar>(R.id.audio_volume_seek)
        audioVolume.max = AUDIO_VOLUME_PERCENT_MAX
        audioVolume.progress = AndroidSettingsStore(this).load().audioVolumePercent
        audioVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        val percent = if (bar.max == 0) 0 else value * JOG_WHEEL_PERCENT_SCALE / bar.max
                        val settingsStore = AndroidSettingsStore(this@PairingActivity)
                        settingsStore.save(settingsStore.load().copy(audioVolumePercent = percent))
                        viewModel.setAudioVolume(percent / AUDIO_VOLUME_PERCENT_MAX.toFloat())
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
        findViewById<SwitchMaterial>(R.id.switch_show_fps).setOnCheckedChangeListener { _, checked ->
            hudVisible = checked
            val store = AndroidSettingsStore(this)
            store.save(store.load().copy(showHud = checked))
            renderHudChip()
        }
        findViewById<SwitchMaterial>(R.id.switch_show_gyro_monitor).setOnCheckedChangeListener { _, checked ->
            gyroMonitorVisible = checked
            findViewById<GyroMonitorView>(R.id.gyro_monitor).isVisible = checked
            findViewById<GyroMonitorView>(R.id.gyro_monitor).bringToFront()
            val store = AndroidSettingsStore(this)
            store.save(store.load().copy(showGyroMonitor = checked))
        }
        findViewById<SwitchMaterial>(R.id.switch_show_controls).setOnCheckedChangeListener { _, checked ->
            controlsVisible = checked
            findViewById<ViewGroup>(R.id.surface_container).isVisible = checked
            val store = AndroidSettingsStore(this)
            store.save(store.load().copy(showControls = checked))
        }
        attachProfileSurface()
    }

    private fun resolutionButtons(): Map<RadioButton, Pair<Int, Int>> =
        mapOf(
            findViewById<RadioButton>(R.id.resolution_960x540) to (RES_W_960 to RES_H_540),
            findViewById<RadioButton>(R.id.resolution_1280x720) to (RES_W_1280 to RES_H_720),
            findViewById<RadioButton>(R.id.resolution_1920x1080) to (RES_W_1920 to RES_H_1080),
        )

    private fun fpsButtons(): Map<RadioButton, Int> =
        mapOf(
            findViewById<RadioButton>(R.id.fps_30) to FPS_30,
            findViewById<RadioButton>(R.id.fps_60) to FPS_60,
        )

    private fun currentFps(): Int = if (findViewById<RadioButton>(R.id.fps_30).isChecked) FPS_30 else FPS_60

    private fun currentResolution(): Pair<Int, Int> =
        when {
            findViewById<RadioButton>(R.id.resolution_1920x1080).isChecked -> RES_W_1920 to RES_H_1080
            findViewById<RadioButton>(R.id.resolution_1280x720).isChecked -> RES_W_1280 to RES_H_720
            else -> RES_W_960 to RES_H_540
        }

    /** Superficie táctil: instanciar el editor/uso una sola vez. */
    private fun attachProfileSurface() {
        if (surfaceRef == null) {
            val surface = ProfileSurface(this) { report -> viewModel.onGamepadInput(report) }
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
