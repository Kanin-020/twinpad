package com.twinpad.android.app.ui

import android.Manifest
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.twinpad.android.app.stream.StreamState
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        viewModel = ViewModelProvider(this)[PairingViewModel::class.java]
        cameraController =
            PairingCameraController(this) { raw ->
                viewModel.onEvent(PairingEvent.QrScanned(raw))
            }
        wireViews()
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
                findViewById<ImageView>(R.id.video_view).setImageBitmap(decoded?.bitmap)
            }
        }
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { renderState(it) }
        }
        lifecycleScope.launch {
            viewModel.streamState.collectLatest { s ->
                findViewById<TextView>(R.id.txt_session_message).text =
                    when (s.state) {
                        StreamState.INTERRUPTED ->
                            getString(R.string.second_screen_reconnecting)
                        else -> getString(R.string.second_screen_inactive)
                    }
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
        chip.text =
            getString(
                R.string.hud_chip_text,
                "%.0f".format(stats.fps),
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
            }
            is PairingUiState.Failure -> {
                session.isVisible = false
                txtStatus.text = ""
                btnCancel.isVisible = false
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

    override fun onPause() {
        super.onPause()
        viewModel.onAppBackgrounded() // SPEC-002 §8.3
    }

    override fun onDestroy() {
        cameraController.release()
        super.onDestroy()
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
