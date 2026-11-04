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
    /** Bitrate fijo del cliente (sin control de usuario). El slider del
     * drawer se eliminó a petición del owner: su default de 30 000 kbps
     * emitía ~3 400 datagrams/s por el UDP §4.4 y desbordaba el lector del
     * teléfono (drops → AUs incompletas → 0 fps). 10 000 kbps queda debajo
     * de la capacidad medida del cliente con margen >2×. */
    val videoBitrateKbps: Int = DEFAULT_VIDEO_BITRATE_KBPS,
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
) {
    fun videoConfigLine(): String =
        buildString {
            append("{\"type\":\"video_config\",\"w\":")
            append(width)
            append(",\"h\":")
            append(height)
            // CR-029: sin fps; emisión sin tope de tasa (SPEC-003 §4.1).
            append(",\"bitrate_kbps\":")
            append(videoBitrateKbps)
            append('}')
        }

    companion object {
        // CR-031: defaults de fábrica 1080p, pantalla "contener". El bitrate
        // queda fijo en 10 000 kbps (sin control de usuario, decisión del owner).
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val DEFAULT_VIDEO_BITRATE_KBPS = 10_000
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
    }
}
