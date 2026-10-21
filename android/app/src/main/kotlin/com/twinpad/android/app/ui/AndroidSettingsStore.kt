package com.twinpad.android.app.ui

import android.content.Context

private const val MIN_STREAM_WIDTH = 960
private const val MAX_STREAM_WIDTH = 1920
private const val MIN_STREAM_HEIGHT = 540
private const val MAX_STREAM_HEIGHT = 1080
private const val MIN_STREAM_FPS = 30
private const val MAX_STREAM_FPS = 60
private const val MIN_JPEG_QUALITY = 1
private const val MAX_JPEG_QUALITY = 100
private const val MIN_AUDIO_VOLUME_PERCENT = 0
private const val MAX_AUDIO_VOLUME_PERCENT = 100

/** Ajustes locales del drawer; nunca persiste datos de Pairing ni claves de sesión. */
data class AndroidSettings(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val fps: Int = DEFAULT_FPS,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    val audioVolumePercent: Int = DEFAULT_AUDIO_VOLUME_PERCENT,
    val showHud: Boolean = true,
    val showControls: Boolean = true,
) {
    fun videoConfigLine(): String =
        buildString {
            append("{\"type\":\"video_config\",\"w\":")
            append(width)
            append(",\"h\":")
            append(height)
            append(",\"fps\":")
            append(fps)
            append(",\"jpeg_quality\":")
            append(jpegQuality)
            append('}')
        }

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_FPS = 30
        const val DEFAULT_JPEG_QUALITY = 80
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
            fps = preferences.getInt(KEY_FPS, AndroidSettings.DEFAULT_FPS),
            jpegQuality = preferences.getInt(KEY_QUALITY, AndroidSettings.DEFAULT_JPEG_QUALITY),
            audioVolumePercent =
                preferences.getInt(KEY_AUDIO_VOLUME, AndroidSettings.DEFAULT_AUDIO_VOLUME_PERCENT),
            showHud = preferences.getBoolean(KEY_HUD, true),
            showControls = preferences.getBoolean(KEY_CONTROLS, true),
        )

    fun save(settings: AndroidSettings) {
        preferences.edit()
            .putInt(KEY_WIDTH, settings.width.coerceIn(MIN_STREAM_WIDTH, MAX_STREAM_WIDTH))
            .putInt(KEY_HEIGHT, settings.height.coerceIn(MIN_STREAM_HEIGHT, MAX_STREAM_HEIGHT))
            .putInt(KEY_FPS, settings.fps.coerceIn(MIN_STREAM_FPS, MAX_STREAM_FPS))
            .putInt(KEY_QUALITY, settings.jpegQuality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY))
            .putInt(
                KEY_AUDIO_VOLUME,
                settings.audioVolumePercent.coerceIn(MIN_AUDIO_VOLUME_PERCENT, MAX_AUDIO_VOLUME_PERCENT),
            )
            .putBoolean(KEY_HUD, settings.showHud)
            .putBoolean(KEY_CONTROLS, settings.showControls)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "twinpad_user_settings"
        const val KEY_WIDTH = "stream_width"
        const val KEY_HEIGHT = "stream_height"
        const val KEY_FPS = "stream_fps"
        const val KEY_QUALITY = "jpeg_quality"
        const val KEY_AUDIO_VOLUME = "audio_volume_percent"
        const val KEY_HUD = "show_hud"
        const val KEY_CONTROLS = "show_controls"
    }
}
