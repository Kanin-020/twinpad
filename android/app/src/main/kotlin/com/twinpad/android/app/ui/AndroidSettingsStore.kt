package com.twinpad.android.app.ui

import android.content.Context

private const val MIN_STREAM_WIDTH = 960
private const val MAX_STREAM_WIDTH = 1920
private const val MIN_STREAM_HEIGHT = 540
private const val MAX_STREAM_HEIGHT = 1080
private const val MIN_AUDIO_VOLUME_PERCENT = 0
private const val MAX_AUDIO_VOLUME_PERCENT = 100

/** CR-034: lente del teléfono que alimenta la superficie CAM de Cemu. */
enum class CameraLens { FRONT, BACK }

/** Ajustes locales del drawer; nunca persiste datos de Pairing ni claves de sesión. */
data class AndroidSettings(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val audioVolumePercent: Int = DEFAULT_AUDIO_VOLUME_PERCENT,
    val showGyroMonitor: Boolean = false,
    val showHud: Boolean = true,
    val showControls: Boolean = true,
    val scaleMode: VideoScaleMode = VideoScaleMode.CONTAIN,
    /** CR-032: distribución de los botones de cara; true = Nintendo. */
    val nintendoDistribution: Boolean = false,
    /** CR-034: envío del video de cámara a Cemu (opt-in del drawer). */
    val cameraStream: Boolean = false,
    /** CR-034: lente usada cuando [cameraStream] está activada. */
    val cameraLens: CameraLens = CameraLens.FRONT,
    /** CR-054: bloque «Métricas avanzadas» del HUD con las etapas del
     * pipeline del Plugin (`video_stage_stats`); opt-in del drawer. */
    val showAdvancedMetrics: Boolean = false,
    /** CR-077: origen interno de los gauges — `true` = HOST (la PC que
     * corre el server, `host_metrics` del CR-076), `false` = el dispositivo
     * Gamepad ([DeviceMetricsSampler]). Desde CR-079 ya no tiene interruptor
     * en el drawer (default PC) pero sigue alimentando la vista de espera.
     */
    val metricsSourceHost: Boolean = true,
    /** CR-079: slider de gauges durante la sesión — `true` = en STREAMING
     * la ventana de espera aparece como hoja arrastrable sobre el video
     * (nace ABIERTA, decisión del owner); `false` = no se muestra. */
    val metricsSliderOverStream: Boolean = true,
) {
    /** CR-087: bitrate derivado de la resolución elegida (540p 10 Mbps,
     * 720p 15 Mbps, 1080p 20 Mbps), sin control de usuario en el drawer
     * (CR-064 lo retiró). Derivado y no almacenado para que cualquier cambio
     * de resolución lo actualice aunque se haga con `copy`. */
    val videoBitrateKbps: Int
        get() = bitrateForResolution(width, height)

    /** Línea `video_config` de la sesión. [bitrateKbps] permite fijar el
     * bitrate cuando la clase de resolución se conoce fuera del drawer
     * (CR-087: «Nativa» con las dimensiones del `stream_start`). */
    fun videoConfigLine(bitrateKbps: Int = videoBitrateKbps): String =
        buildString {
            append("{\"type\":\"video_config\",\"w\":")
            append(width)
            append(",\"h\":")
            append(height)
            // CR-029: sin fps; emisión sin tope de tasa (SPEC-003 §4.1).
            append(",\"bitrate_kbps\":")
            append(bitrateKbps)
            append('}')
        }

    companion object {
        // CR-031: defaults de fábrica 1080p, pantalla "contener". El bitrate
        // (20 000 kbps con 1080p) se deriva de la resolución (CR-087).
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_AUDIO_VOLUME_PERCENT = 100
    }
}

/** Guardado síncrono pequeño en memoria de SharedPreferences; no bloquea con I/O de archivos. */
internal class AndroidSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AndroidSettings =
        AndroidSettings(
            width = preferences.getInt(KEY_WIDTH, AndroidSettings.DEFAULT_WIDTH),
            height = preferences.getInt(KEY_HEIGHT, AndroidSettings.DEFAULT_HEIGHT),
            audioVolumePercent =
                preferences.getInt(KEY_AUDIO_VOLUME, AndroidSettings.DEFAULT_AUDIO_VOLUME_PERCENT),
            showGyroMonitor = preferences.getBoolean(KEY_GYRO_MONITOR, false),
            showHud = preferences.getBoolean(KEY_HUD, true),
            showControls = preferences.getBoolean(KEY_CONTROLS, true),
            // CR-031: el default pasa a CONTAIN; EXPAND solo si quedó persistido.
            scaleMode =
                if (preferences.getString(KEY_SCALE_MODE, null) == VideoScaleMode.EXPAND.name) {
                    VideoScaleMode.EXPAND
                } else {
                    VideoScaleMode.CONTAIN
                },
            nintendoDistribution = preferences.getBoolean(KEY_NINTENDO_DISTRIBUTION, false),
            cameraStream = preferences.getBoolean(KEY_CAMERA_STREAM, false),
            cameraLens =
                if (preferences.getString(KEY_CAMERA_LENS, null) == CameraLens.BACK.name) {
                    CameraLens.BACK
                } else {
                    CameraLens.FRONT
                },
            // CR-054: métricas avanzadas opt-in, off por defecto.
            showAdvancedMetrics = preferences.getBoolean(KEY_ADVANCED_METRICS, false),
            // CR-077: los gauges muestran la PC por defecto (CR-076 v2).
            metricsSourceHost = preferences.getBoolean(KEY_METRICS_SOURCE_HOST, true),
            metricsSliderOverStream =
                preferences.getBoolean(KEY_METRICS_SLIDER_OVER_STREAM, true),
        )

    fun save(settings: AndroidSettings) {
        // CR-074: «Nativa» persiste 0/0 sin clamp; cualquier otro par baja
        // al rango 960×540–1920×1080 del drawer.
        val isNative = settings.width == 0 && settings.height == 0
        preferences.edit()
            .putInt(
                KEY_WIDTH,
                if (isNative) 0 else settings.width.coerceIn(MIN_STREAM_WIDTH, MAX_STREAM_WIDTH),
            )
            .putInt(
                KEY_HEIGHT,
                if (isNative) 0 else settings.height.coerceIn(MIN_STREAM_HEIGHT, MAX_STREAM_HEIGHT),
            )
            .putInt(
                KEY_AUDIO_VOLUME,
                settings.audioVolumePercent.coerceIn(MIN_AUDIO_VOLUME_PERCENT, MAX_AUDIO_VOLUME_PERCENT),
            )
            .putBoolean(KEY_GYRO_MONITOR, settings.showGyroMonitor)
            .putBoolean(KEY_HUD, settings.showHud)
            .putBoolean(KEY_CONTROLS, settings.showControls)
            .putString(KEY_SCALE_MODE, settings.scaleMode.name)
            .putBoolean(KEY_NINTENDO_DISTRIBUTION, settings.nintendoDistribution)
            .putBoolean(KEY_CAMERA_STREAM, settings.cameraStream)
            .putString(KEY_CAMERA_LENS, settings.cameraLens.name)
            .putBoolean(KEY_ADVANCED_METRICS, settings.showAdvancedMetrics)
            .putBoolean(KEY_METRICS_SOURCE_HOST, settings.metricsSourceHost)
            .putBoolean(KEY_METRICS_SLIDER_OVER_STREAM, settings.metricsSliderOverStream)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "twinpad_user_settings"
        const val KEY_WIDTH = "stream_width"
        const val KEY_HEIGHT = "stream_height"
        const val KEY_AUDIO_VOLUME = "audio_volume_percent"
        const val KEY_GYRO_MONITOR = "show_gyro_monitor"
        const val KEY_HUD = "show_hud"
        const val KEY_CONTROLS = "show_controls"
        const val KEY_SCALE_MODE = "video_scale_mode"
        const val KEY_NINTENDO_DISTRIBUTION = "nintendo_distribution"
        const val KEY_CAMERA_STREAM = "camera_stream"
        const val KEY_CAMERA_LENS = "camera_lens"
        const val KEY_ADVANCED_METRICS = "show_advanced_metrics"
        const val KEY_METRICS_SOURCE_HOST = "metrics_source_host"
        const val KEY_METRICS_SLIDER_OVER_STREAM = "metrics_slider_over_stream"
    }
}
