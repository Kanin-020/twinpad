package com.twinpad.android.app.ui

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
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
import kotlin.math.roundToInt

private const val MICROSECONDS_PER_MILLISECOND = 1_000.0
private const val RADIANS_PER_TURN = 2f * Math.PI.toFloat()

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
    private var pendingFrameNumber: Long? = null
    private var pendingFramePublishedAtNanos = 0L
    private lateinit var sensorManager: SensorManager
    private var rotationVector: Sensor? = null
    private val orientationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val sensorListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(orientationMatrix, event.values)
                SensorManager.getOrientation(orientationMatrix, orientationAngles)
                val scale = ProtocolConstants.GYRO_TURN_SCALE
                surfaceRef?.updateGyro(
                    listOf(
                        radiansToTurns(orientationAngles[0], scale),
                        radiansToTurns(orientationAngles[1], scale),
                        radiansToTurns(orientationAngles[2], scale),
                    ),
                )
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
        setContentView(R.layout.activity_pairing)
        viewModel = ViewModelProvider(this)[PairingViewModel::class.java]
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        cameraController =
            PairingCameraController(this) { raw ->
                viewModel.onEvent(PairingEvent.QrScanned(raw))
            }
        wireViews()
        observeCameraEffects()
        observeStates()
        observeVideoPresentation()
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
            findViewById<DrawerLayout>(R.id.settings_drawer).openDrawer(Gravity.START)
        }
        findViewById<TextView>(R.id.editor_entry).setOnClickListener {
            surfaceRef?.toggleEditing()
        }
        findViewById<Button>(R.id.btn_close_drawer).setOnClickListener {
            findViewById<DrawerLayout>(R.id.settings_drawer).closeDrawer(Gravity.START)
        }
    }

    /** Observa la sesión completa (video por frame, HUD por tick). */
    private fun observeStates() {
        lifecycleScope.launch {
            viewModel.lastFrame.collectLatest { decoded ->
                val videoView = findViewById<ImageView>(R.id.video_view)
                videoView.setImageBitmap(decoded?.bitmap)
                pendingFrameNumber = decoded?.frameNumber
                pendingFramePublishedAtNanos = if (decoded != null) System.nanoTime() else 0L
                if (decoded != null) videoView.invalidate()
            }
        }
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { renderState(it) }
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
                updateKeepScreenOn(s.state)
            }
        }
        lifecycleScope.launch {
            viewModel.availableGames.collectLatest { games ->
                findViewById<TextView>(R.id.txt_games).text = gamesText(games)
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

    private fun observeVideoPresentation() {
        findViewById<ImageView>(R.id.video_view).viewTreeObserver.addOnPreDrawListener {
            val frameNumber = pendingFrameNumber
            if (frameNumber != null) {
                val presentationDelay = System.nanoTime() - pendingFramePublishedAtNanos
                viewModel.streamStats.recordStage(
                    com.twinpad.android.app.stream.StreamStats.VideoStage.UI_PUBLISH_TO_DRAW,
                    presentationDelay,
                )
                com.twinpad.android.app.stream.VideoTrace.section("video.present") {}
                pendingFrameNumber = null
                pendingFramePublishedAtNanos = 0L
                val snapshot = viewModel.streamStats.snapshot()
                snapshot.stageAverageMs.forEach { (stage, averageMs) ->
                    com.twinpad.android.app.stream.VideoTrace.counter(
                        "video.stage_${stage.name.lowercase()}_us",
                        (averageMs * MICROSECONDS_PER_MILLISECOND).toLong(),
                    )
                }
                viewModel.streamStats.onFramePresented()
            }
            true
        }
    }

    /** Segunda pantalla activa = pantalla del teléfono encendida (pedido
     * del owner: el video no debe apagar el display mientras corre).
     * `FLAG_KEEP_SCREEN_ON` es la vía recomendada: sin permisos, se libera
     * al salir del estado STREAMING (o al destruir la Activity). */
    private fun updateKeepScreenOn(state: StreamState) {
        val window = window
        if (state == StreamState.STREAMING || state == StreamState.INTERRUPTED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
    private fun wireDrawerControls() {
        resolutionButtons().forEach { (button, dims) ->
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) viewModel.onVideoQualityChanged(dims.first, dims.second, currentFps())
            }
        }
        fpsButtons().forEach { (button, fps) ->
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) viewModel.onVideoQualityChanged(currentWidth(), currentHeight(), fps)
            }
        }
        findViewById<SeekBar>(R.id.jpeg_quality_seek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) viewModel.onJpegQualityChanged(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
        findViewById<SwitchMaterial>(R.id.switch_show_fps).setOnCheckedChangeListener { _, checked ->
            hudVisible = checked
        }
        findViewById<SwitchMaterial>(R.id.switch_show_controls).setOnCheckedChangeListener { _, checked ->
            controlsVisible = checked
            findViewById<ViewGroup>(R.id.surface_container).isVisible = checked
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

    private fun currentWidth(): Int =
        when {
            findViewById<RadioButton>(R.id.resolution_1920x1080).isChecked -> RES_W_1920
            findViewById<RadioButton>(R.id.resolution_1280x720).isChecked -> RES_W_1280
            else -> RES_W_960
        }

    private fun currentHeight(): Int =
        when {
            findViewById<RadioButton>(R.id.resolution_1920x1080).isChecked -> RES_H_1080
            findViewById<RadioButton>(R.id.resolution_1280x720).isChecked -> RES_H_720
            else -> RES_H_540
        }

    /** Superficie táctil: instanciar el editor/uso una sola vez. */
    private fun attachProfileSurface() {
        if (surfaceRef == null) {
            val surface = ProfileSurface(this) { report -> viewModel.onGamepadInput(report) }
            surfaceRef = surface
            findViewById<ViewGroup>(R.id.surface_container).addView(surface)
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

    override fun onResume() {
        super.onResume()
        rotationVector?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_GAME,
                ProtocolConstants.SENSOR_REPORT_INTERVAL_US,
            )
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(sensorListener)
        super.onPause()
        viewModel.onAppBackgrounded() // SPEC-002 §8.3
    }

    override fun onDestroy() {
        cameraController.release()
        SessionForegroundService.stop(this)
        super.onDestroy()
    }
}

/** Convierte radianes absolutos a vueltas int16 según el contrato de input. */
private fun radiansToTurns(
    radians: Float,
    scale: Float,
): Int =
    (radians / RADIANS_PER_TURN * scale)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

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
