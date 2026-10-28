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
        host.onHudRestored(settings.showHud)
        host.onControlsToggled(settings.showControls)
        host.onGyroMonitorToggled(settings.showGyroMonitor)
        activity.findViewById<SwitchMaterial>(R.id.switch_show_gyro_monitor).isChecked = settings.showGyroMonitor
        val scaleButtonId = if (settings.scaleMode == VideoScaleMode.CONTAIN) R.id.scale_contain else R.id.scale_expand
        activity.findViewById<RadioButton>(scaleButtonId).isChecked = true
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

    private fun currentResolution(): Pair<Int, Int> =
        when {
            activity.findViewById<RadioButton>(R.id.resolution_1920x1080).isChecked -> RES_W_1920 to RES_H_1080
            activity.findViewById<RadioButton>(R.id.resolution_1280x720).isChecked -> RES_W_1280 to RES_H_720
            else -> RES_W_960 to RES_H_540
        }
}
