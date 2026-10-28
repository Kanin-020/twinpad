package com.twinpad.android.app.stream

import android.graphics.Bitmap
import android.graphics.Color
import com.twinpad.android.app.wire.ProtocolConstants

/**
 * Presentación del Framebuffer (SPEC-003 §5/§7 paso 3): convierte el buffer
 * crudo del stream al [Bitmap] que la vista muestra. Puro respecto del
 * contenido: mismo input → mismo bitmap (ENG P10); la única dependencia de
 * plataforma es `android.graphics` (frontera de render declarada).
 */
object FrameRenderer {
    /** Bytes por píxel por formato (paridad con `core::stream`); `null` si
     * el formato es comprimido o no es de la v1. */
    private fun bpp(fmt: String): Int? =
        when (fmt) {
            "gray8" -> ProtocolConstants.GRAY_BYTES_PER_PIXEL
            "rgb8" -> ProtocolConstants.RGB_BYTES_PER_PIXEL
            else -> null
        }

    /**
     * Convierte `data` a ARGB para render. `jpeg` (CR-001) se decodifica con
     * `BitmapFactory` (acelerado por hardware); los formatos crudos se
     * rellenan píxel a píxel. Devuelve el bitmap del frame o `null` si el
     * buffer no es consistente (SPEC-003 §5: el frame corrupto se descarta,
     * nunca se muestra basura).
     */
    fun decode(
        data: ByteArray,
        width: Int,
        height: Int,
        fmt: String,
    ): Bitmap? =
        when (fmt) {
            "jpeg" -> decodeJpeg(data, width, height)
            else -> decodeRaw(data, width, height, fmt)
        }

    /** JPEG completo (JFIF con cabecera) → bitmap; el tamaño lo declara el
     * archivo y debe coincidir con el anunciado (§5: si no, se descarta). */
    private fun decodeJpeg(
        data: ByteArray,
        width: Int,
        height: Int,
    ): Bitmap? {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        if (bitmap.width != width || bitmap.height != height) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun decodeRaw(
        data: ByteArray,
        width: Int,
        height: Int,
        fmt: String,
    ): Bitmap? {
        val bytesPerPixel = bpp(fmt) ?: return null
        val expectedSize = width * height * bytesPerPixel
        val dimensionsOk = width > 0 && height > 0 && data.size == expectedSize
        val bitmap = if (dimensionsOk) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) else null
        return bitmap?.also {
            fillPixels(it, Frame(data, width, height, bytesPerPixel, fmt))
        }
    }

    /** Parámetros de decodificación agrupados (ENG P4: ≤ 3 posicionales). */
    private data class Frame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val bytesPerPixel: Int,
        val fmt: String,
    )

    private fun fillPixels(
        bitmap: Bitmap,
        frame: Frame,
    ) {
        val pixels = IntArray(frame.width * frame.height)
        var i = 0
        when (frame.fmt) {
            "rgb8" ->
                for (y in 0 until frame.height) {
                    for (x in 0 until frame.width) {
                        val r = frame.data[i].toInt() and ProtocolConstants.BYTE_MASK
                        val g = frame.data[i + 1].toInt() and ProtocolConstants.BYTE_MASK
                        val b = frame.data[i + 2].toInt() and ProtocolConstants.BYTE_MASK
                        pixels[y * frame.width + x] = Color.rgb(r, g, b)
                        i += frame.bytesPerPixel
                    }
                }
            else ->
                for (y in 0 until frame.height) {
                    for (x in 0 until frame.width) {
                        val v = frame.data[i].toInt() and ProtocolConstants.BYTE_MASK
                        pixels[y * frame.width + x] = Color.rgb(v, v, v)
                        i += frame.bytesPerPixel
                    }
                }
        }
        bitmap.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    }
}
