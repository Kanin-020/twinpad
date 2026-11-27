package com.twinpad.android.app.ui

import android.view.MotionEvent
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.twinpad.android.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** Resoluciones seleccionables (16:9, CR-001). */
private const val RES_W_960 = 960
private const val RES_W_NATIVE = 0
private const val RES_H_NATIVE = 0
private const val RES_H_540 = 540
private const val RES_W_1280 = 1280
private const val RES_H_720 = 720
private const val RES_W_1920 = 1920
private const val RES_H_1080 = 1080

private const val AUDIO_VOLUME_PERCENT_MAX = 100

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

    /** CR-054: bloque «Métricas avanzadas» del HUD activado/desactivado. */
    fun onAdvancedMetricsToggled(enabled: Boolean)

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
    /** CR-137: ajuste «Activar envío NFC» vigente; lo consume el lector
     * `amiibo_tag` (CR-126). Se pasa como estado para no sumar otra función a
     * la interfaz del drawer (detekt TooManyFunctions). */
    private val nfcSend: MutableStateFlow<Boolean>,
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
        activity.findViewById<Slider>(R.id.audio_volume_seek).value = settings.audioVolumePercent.toFloat()
        activity.findViewById<SwitchMaterial>(R.id.switch_show_fps).isChecked = settings.showHud
        activity.findViewById<SwitchMaterial>(R.id.switch_show_controls).isChecked = settings.showControls
        activity.findViewById<SwitchMaterial>(R.id.switch_nintendo_distribution).isChecked =
            settings.nintendoDistribution
        host.onHudRestored(settings.showHud)
        host.onControlsToggled(settings.showControls)
        host.onNintendoDistributionToggled(settings.nintendoDistribution)
        host.onGyroMonitorToggled(settings.showGyroMonitor)
        activity.findViewById<SwitchMaterial>(R.id.switch_show_gyro_monitor).isChecked = settings.showGyroMonitor
        activity.findViewById<SwitchMaterial>(R.id.switch_advanced_metrics).isChecked = settings.showAdvancedMetrics
        host.onAdvancedMetricsToggled(settings.showAdvancedMetrics)
        // CR-077: el interruptor solo se marca; la restauración del origen
        // vive en la Activity (host.onStreamSettingsChanged no corre aquí).
        // CR-079: el switch gobierna el slider de gauges en sesión.
        activity.findViewById<SwitchMaterial>(R.id.switch_metrics_slider).isChecked =
            settings.metricsSliderOverStream
        // CR-090: el interruptor del micrófono se marca aquí; el host lee la
        // preferencia persistida al evaluar la demanda (CR-079: sin función
        // nueva en la interfaz del drawer, detekt TooManyFunctions).
        activity.findViewById<SwitchMaterial>(R.id.switch_microphone_stream).isChecked =
            settings.microphoneStream
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
        // CR-137: interruptor del envío NFC debajo de la cámara.
        activity.findViewById<SwitchMaterial>(R.id.switch_nfc_send).isChecked = settings.nfcSend
        nfcSend.value = settings.nfcSend
        host.applyVideoScale(settings)
    }

    /** Cablea cada control del drawer a su efecto. */
    fun bind() {
        bindResolutionButtons()
        bindScaleButtons()
        bindAudioVolumeSeek()
        bindVisibilitySwitches()
        bindMicrophoneStreamControl()
        bindCameraStreamControls()
    }

    /** CR-090: el interruptor del micrófono persiste y notifica al host por la
     * ruta de ajustes existente; el host reevalúa la demanda con el valor
     * guardado y no reenvía `video_config` si la tupla de video no cambió. */
    private fun bindMicrophoneStreamControl() {
        activity.findViewById<SwitchMaterial>(R.id.switch_microphone_stream)
            .setOnCheckedChangeListener { _, checked ->
                updateStreamSettings { it.copy(microphoneStream = checked) }
            }
    }

    private fun bindResolutionButtons() {
        resolutionButtons().forEach { (button, dims) ->
            button.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    updateStreamSettings {
                        it.copy(width = dims.first, height = dims.second)
                    }
                }
            }
        }
    }

    private fun bindScaleButtons() {
        activity.findViewById<RadioButton>(R.id.scale_contain).setOnCheckedChangeListener { _, checked ->
            if (checked) updateStreamSettings { it.copy(scaleMode = VideoScaleMode.CONTAIN) }
        }
        activity.findViewById<RadioButton>(R.id.scale_expand).setOnCheckedChangeListener { _, checked ->
            if (checked) updateStreamSettings { it.copy(scaleMode = VideoScaleMode.EXPAND) }
        }
    }

    /** CR-001: el volumen del drawer persiste sin notificar al ViewModel.
     * El arrastre del slider se aísla del scroll del drawer y del gesto del
     * `DrawerLayout` para que el pulgar siga al dedo en todo su recorrido. */
    private fun bindAudioVolumeSeek() {
        val audioVolume = activity.findViewById<Slider>(R.id.audio_volume_seek)
        audioVolume.value = store.load().audioVolumePercent.toFloat()
        audioVolume.setOnTouchListener { view, event ->
            val dragging =
                event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL
            view.parent?.requestDisallowInterceptTouchEvent(dragging)
            false
        }
        audioVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val percent = value.roundToInt().coerceIn(0, AUDIO_VOLUME_PERCENT_MAX)
                persist { it.copy(audioVolumePercent = percent) }
                host.updateAudioVolume(percent / AUDIO_VOLUME_PERCENT_MAX.toFloat())
            }
        }
    }

    /** Interruptores de visualización: HUD, giroscopio, controles,
     * distribución Nintendo (CR-032), métricas avanzadas (CR-054) y origen
     * de los gauges de espera (CR-077). */
    private fun bindVisibilitySwitches() {
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
        // CR-054: métricas por etapa del Plugin; el host re-renderiza el HUD.
        activity.findViewById<SwitchMaterial>(R.id.switch_advanced_metrics)
            .setOnCheckedChangeListener { _, checked ->
                host.onAdvancedMetricsToggled(checked)
                persist { it.copy(showAdvancedMetrics = checked) }
            }
        // CR-079: «Mostrar slider de métricas» — muestra/oculta la hoja de
        // gauges durante el stream; viaja por la ruta de ajustes existente
        // (sin función nueva de interfaz: detekt TooManyFunctions) y aplica
        // en caliente vía el tick de gauges que re-consulta el ajuste.
        activity.findViewById<SwitchMaterial>(R.id.switch_metrics_slider)
            .setOnCheckedChangeListener { _, checked ->
                updateStreamSettings { it.copy(metricsSliderOverStream = checked) }
            }
        // CR-137: el envío NFC persiste y el lector lo observa; apagarlo
        // desmonta el tag vigente (CR-126).
        activity.findViewById<SwitchMaterial>(R.id.switch_nfc_send)
            .setOnCheckedChangeListener { _, checked ->
                nfcSend.value = checked
                persist { it.copy(nfcSend = checked) }
            }
    }

    /** CR-034: toggle + lente; el host decide arrancar/parar/reiniciar. */
    private fun bindCameraStreamControls() {
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
            activity.findViewById<RadioButton>(R.id.resolution_native) to (RES_W_NATIVE to RES_H_NATIVE),
            activity.findViewById<RadioButton>(R.id.resolution_960x540) to (RES_W_960 to RES_H_540),
            activity.findViewById<RadioButton>(R.id.resolution_1280x720) to (RES_W_1280 to RES_H_720),
            activity.findViewById<RadioButton>(R.id.resolution_1920x1080) to (RES_W_1920 to RES_H_1080),
        )
}
