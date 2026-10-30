package com.twinpad.android.app.ui

import android.widget.RadioButton
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.twinpad.android.app.R

/** Resoluciones seleccionables (16:9, CR-001). */
private const val RES_W_960 = 960
private const val RES_H_540 = 540
private const val RES_W_1280 = 1280
private const val RES_H_720 = 720
private const val RES_W_1920 = 1920
private const val RES_H_1080 = 1080

private const val AUDIO_VOLUME_PERCENT_MAX = 100
private const val JOG_WHEEL_PERCENT_SCALE = 100

/** Rango del seek de bitrate: slider 0…29000 + offset = 1000…30000 kbps. */
private const val BITRATE_SEEK_KBPS_MIN = 1_000

/** Efectos que exceden al drawer y ejecuta el host (PairingActivity). */
internal interface SettingsDrawerHost {
    fun applyVideoScale(settings: AndroidSettings)

    fun onStreamSettingsChanged(settings: AndroidSettings)

    fun updateAudioVolume(volume: Float)

    /** Interruptor show_hud: además vuelve a renderizar el chip. */
    fun onHudToggled(visible: Boolean)

    /** Restauración inicial (onCreate): solo el flag, sin efectos colaterales. */
    fun onHudRestored(visible: Boolean)

    fun onGyroMonitorToggled(visible: Boolean)

    fun onControlsToggled(visible: Boolean)

    /** CR-032: distribución Nintendo activada/desactivada. Restauración
     * inicial y cambio de usuario comparten esta notificación. */
    fun onNintendoDistributionToggled(enabled: Boolean)

    /** CR-034: stream de cámara (on/off) con la lente vigente. Restauración
     * inicial y cambio de usuario comparten esta notificación; el host
     * reevalúa la demanda (CR-038) y arranca/para/reinicia la lente. */
    fun onCameraStreamToggled(
        enabled: Boolean,
        lens: CameraLens,
    )
}

/**
 * Dueño del drawer de ajustes (SPEC-002 §6): marca sus controles con los
 * valores persistidos ([applyPersistedSettings]), cablea sus listeners
 * ([bind]) y centraliza toda la E/S de [AndroidSettings] en una única
 * instancia de [AndroidSettingsStore].
 */
internal class SettingsDrawerController(
    private val activity: AppCompatActivity,
    private val host: SettingsDrawerHost,
) {
    private val store = AndroidSettingsStore(activity)

    /** Carga los ajustes persistidos (única instancia del store). */
    fun load(): AndroidSettings = store.load()

    /** Marca los controles del drawer con los valores persistidos. */
    fun applyPersistedSettings() {
        val settings = store.load()
        host.updateAudioVolume(settings.audioVolumePercent / AUDIO_VOLUME_PERCENT_MAX.toFloat())
        resolutionButtons().forEach { (button, dims) ->
            if (dims.first == settings.width && dims.second == settings.height) button.isChecked = true
        }
        val knownResolution =
            resolutionButtons().values.any { dims -> dims.first == settings.width && dims.second == settings.height }
        if (!knownResolution) {
            // CR-031: el default del drawer es 1080p.
            activity.findViewById<RadioButton>(R.id.resolution_1920x1080).isChecked = true
        }
        activity.findViewById<SeekBar>(R.id.bitrate_seek).progress =
            settings.videoBitrateKbps - BITRATE_SEEK_KBPS_MIN
        activity.findViewById<SeekBar>(R.id.audio_volume_seek).progress = settings.audioVolumePercent
        activity.findViewById<SwitchMaterial>(R.id.switch_show_fps).isChecked = settings.showHud
        activity.findViewById<SwitchMaterial>(R.id.switch_show_controls).isChecked = settings.showControls
        activity.findViewById<SwitchMaterial>(R.id.switch_nintendo_distribution).isChecked =
            settings.nintendoDistribution
        host.onHudRestored(settings.showHud)
        host.onControlsToggled(settings.showControls)
        host.onNintendoDistributionToggled(settings.nintendoDistribution)
        host.onGyroMonitorToggled(settings.showGyroMonitor)
        activity.findViewById<SwitchMaterial>(R.id.switch_show_gyro_monitor).isChecked = settings.showGyroMonitor
        activity.findViewById<SwitchMaterial>(R.id.switch_camera_stream).isChecked = settings.cameraStream
        val lensCheckedId =
            if (settings.cameraLens == CameraLens.FRONT) R.id.camera_lens_front else R.id.camera_lens_back
        activity.findViewById<RadioButton>(lensCheckedId).isChecked = true
        val scaleButtonId =
            if (settings.scaleMode == VideoScaleMode.CONTAIN) R.id.scale_contain else R.id.scale_expand
        activity.findViewById<RadioButton>(scaleButtonId).isChecked = true
        activity.findViewById<SwitchMaterial>(R.id.switch_camera_stream).isChecked = settings.cameraStream
        val lensButtonId =
            if (settings.cameraLens == CameraLens.FRONT) R.id.camera_lens_front else R.id.camera_lens_back
        activity.findViewById<RadioButton>(lensButtonId).isChecked = true
        host.onCameraStreamToggled(settings.cameraStream, settings.cameraLens)
        host.applyVideoScale(settings)
    }

    /** Cablea cada control del drawer a su efecto. */
    fun bind() {
        resolutionButtons().forEach { (button, dims) ->
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    updateStreamSettings {
                        it.copy(width = dims.first, height = dims.second)
                    }
                }
            }
        }
        activity.findViewById<RadioButton>(R.id.scale_contain).setOnCheckedChangeListener { _, checked ->
            if (checked) updateStreamSettings { it.copy(scaleMode = VideoScaleMode.CONTAIN) }
        }
        activity.findViewById<RadioButton>(R.id.scale_expand).setOnCheckedChangeListener { _, checked ->
            if (checked) updateStreamSettings { it.copy(scaleMode = VideoScaleMode.EXPAND) }
        }
        activity.findViewById<SeekBar>(R.id.bitrate_seek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        updateStreamSettings { it.copy(videoBitrateKbps = value + BITRATE_SEEK_KBPS_MIN) }
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
        val audioVolume = activity.findViewById<SeekBar>(R.id.audio_volume_seek)
        audioVolume.max = AUDIO_VOLUME_PERCENT_MAX
        audioVolume.progress = store.load().audioVolumePercent
        audioVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        val percent = if (bar.max == 0) 0 else value * JOG_WHEEL_PERCENT_SCALE / bar.max
                        persist { it.copy(audioVolumePercent = percent) }
                        host.updateAudioVolume(percent / AUDIO_VOLUME_PERCENT_MAX.toFloat())
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
        activity.findViewById<SwitchMaterial>(R.id.switch_show_fps).setOnCheckedChangeListener { _, checked ->
            host.onHudToggled(checked)
            persist { it.copy(showHud = checked) }
        }
        activity.findViewById<SwitchMaterial>(R.id.switch_show_gyro_monitor).setOnCheckedChangeListener { _, checked ->
            host.onGyroMonitorToggled(checked)
            persist { it.copy(showGyroMonitor = checked) }
        }
        activity.findViewById<SwitchMaterial>(R.id.switch_show_controls).setOnCheckedChangeListener { _, checked ->
            host.onControlsToggled(checked)
            persist { it.copy(showControls = checked) }
        }
        // CR-032: el swap exacto lo ejecuta el host (superficie y mando).
        activity.findViewById<SwitchMaterial>(R.id.switch_nintendo_distribution)
            .setOnCheckedChangeListener { _, checked ->
                host.onNintendoDistributionToggled(checked)
                persist { it.copy(nintendoDistribution = checked) }
            }
        // CR-034: toggle + lente; el host decide arrancar/parar/reiniciar.
        activity.findViewById<SwitchMaterial>(R.id.switch_camera_stream)
            .setOnCheckedChangeListener { _, checked ->
                val lens = store.load().cameraLens
                host.onCameraStreamToggled(checked, lens)
                persist { it.copy(cameraStream = checked) }
            }
        val lensFront = activity.findViewById<RadioButton>(R.id.camera_lens_front)
        val lensBack = activity.findViewById<RadioButton>(R.id.camera_lens_back)
        lensFront.setOnCheckedChangeListener { _, checked -> updateCameraLens(checked, CameraLens.FRONT) }
        lensBack.setOnCheckedChangeListener { _, checked -> updateCameraLens(checked, CameraLens.BACK) }
    }

    /** CR-034: cambia la lente persistida; si el stream estaba activo, el
     * host recibe la notificación para reiniciar con la lente nueva. */
    private fun updateCameraLens(
        checked: Boolean,
        lens: CameraLens,
    ) {
        if (!checked) return
        val current = store.load()
        if (current.cameraLens == lens) return
        val updated = current.copy(cameraLens = lens)
        store.save(updated)
        host.onCameraStreamToggled(current.cameraStream, lens)
    }

    /** Persiste sin notificar al ViewModel (audio y visibilidad del HUD). */
    private fun persist(transform: (AndroidSettings) -> AndroidSettings) {
        store.save(transform(store.load()))
    }

    /** Persiste y notifica el cambio de configuración de stream al host. */
    private fun updateStreamSettings(transform: (AndroidSettings) -> AndroidSettings) {
        val updated = transform(store.load())
        store.save(updated)
        host.onStreamSettingsChanged(updated)
        host.applyVideoScale(updated)
    }

    private fun resolutionButtons(): Map<RadioButton, Pair<Int, Int>> =
        mapOf(
            activity.findViewById<RadioButton>(R.id.resolution_960x540) to (RES_W_960 to RES_H_540),
            activity.findViewById<RadioButton>(R.id.resolution_1280x720) to (RES_W_1280 to RES_H_720),
            activity.findViewById<RadioButton>(R.id.resolution_1920x1080) to (RES_W_1920 to RES_H_1080),
        )
}
