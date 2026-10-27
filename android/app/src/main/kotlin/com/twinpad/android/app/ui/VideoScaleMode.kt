package com.twinpad.android.app.ui

/** Modo de escalado de la Segunda pantalla (CR-025). `EXPAND` es el valor por defecto. */
enum class VideoScaleMode {
    /** Conserva la relación de aspecto del video y deja barras en los bordes sobrantes. */
    CONTAIN,

    /** Estira el video para ocupar todo el contenedor. */
    EXPAND,
}

/** Rectángulo de la imagen dentro del contenedor, en fracciones 0..1 del contenedor. */
data class VideoContentRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** Entradas no utilizables: contenedor sin área o video sin dimensiones. */
private fun invalidVideoFrame(
    containerWidth: Float,
    containerHeight: Float,
    videoWidth: Int,
    videoHeight: Int,
): Boolean =
    containerWidth <= 0f || containerHeight <= 0f ||
        videoWidth <= 0 || videoHeight <= 0

/**
 * Calcula dónde se dibuja la imagen del video dentro del contenedor.
 * En `EXPAND` ocupa todo el contenedor; en `CONTAIN` conserva la relación de aspecto
 * de `videoWidth` x `videoHeight` y centra la imagen con barras.
 */
fun videoContentRect(
    mode: VideoScaleMode,
    containerWidth: Float,
    containerHeight: Float,
    videoWidth: Int,
    videoHeight: Int,
): VideoContentRect {
    if (mode == VideoScaleMode.EXPAND || invalidVideoFrame(containerWidth, containerHeight, videoWidth, videoHeight)) {
        return VideoContentRect(0f, 0f, 1f, 1f)
    }
    val containerAspect = containerWidth / containerHeight
    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    return if (videoAspect > containerAspect) {
        val height = containerAspect / videoAspect
        VideoContentRect(0f, (1f - height) / 2f, 1f, height)
    } else {
        val width = videoAspect / containerAspect
        VideoContentRect((1f - width) / 2f, 0f, width, 1f)
    }
}
