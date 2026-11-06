package com.twinpad.android.app.ui

/**
 * CR-087: bitrate objetivo del encoder H.264 según la resolución de emisión
 * (SPEC-003 §4.1 `video_config.bitrate_kbps`, rango 1 000–30 000 kbps).
 *
 * Valores dimensionados para 60 fps sostenidos de contenido de juego. La
 * referencia pública de H.264 (YouTube Live) sitúa 720p60 en 8 Mbps y
 * 1080p60 en 17 Mbps recomendados; el margen extra cubre el movimiento de
 * juego y deja holgura al rate-control. En este proyecto el límite real es
 * la recepción UDP del teléfono (CR-064/CR-083), no la calidad teórica.
 */
internal const val BITRATE_540P_KBPS = 10_000
internal const val BITRATE_720P_KBPS = 15_000
internal const val BITRATE_1080P_KBPS = 20_000

private const val HEIGHT_540P = 540
private const val HEIGHT_720P = 720

/**
 * Bitrate del drawer para un par de dimensiones: 540p → 10 Mbps, 720p →
 * 15 Mbps, 1080p y superiores → 20 Mbps. Con «Nativa» (0/0) el drawer no
 * conoce el tamaño real: parte de la clase 540p (el DRC nativo típico es
 * 854×480) y la app lo ajusta con las dimensiones del `stream_start`.
 */
fun bitrateForResolution(
    width: Int,
    height: Int,
): Int =
    when {
        width <= 0 || height <= 0 -> BITRATE_540P_KBPS
        height <= HEIGHT_540P -> BITRATE_540P_KBPS
        height <= HEIGHT_720P -> BITRATE_720P_KBPS
        else -> BITRATE_1080P_KBPS
    }
