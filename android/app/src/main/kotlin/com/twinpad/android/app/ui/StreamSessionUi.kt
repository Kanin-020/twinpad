package com.twinpad.android.app.ui

/**
 * Ajustes del usuario para la vista de sesión (sidebar ⚙).
 */
data class StreamSettings(
    val showFps: Boolean = true,
    val showControls: Boolean = true,
    /** Última calidad pedida al server (para marcar la selección). */
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
)

/**
 * Estado observable de la sesión agrupado para la vista (ENG P4: ≤ 3
 * posicionales; la vista de sesión recibe este bundle en lugar de 8
 * parámetros sueltos).
 */
data class SessionUiBundle(
    val streamState: com.twinpad.android.app.stream.StreamUiState?,
    val lastFrame: com.twinpad.android.app.stream.DecodedFrame?,
    val stats: com.twinpad.android.app.stream.StreamStats?,
    val availableGames: List<com.twinpad.android.app.wire.GameInfo>,
    val settings: StreamSettings,
    val actions: PairingActions,
)

/**
 * Callbacks de ajustes de la vista de sesión (ENG P4: evita un
 * LongParameterList en PairingScreen).
 */
data class SessionSettingsCallbacks(
    val onSettingsChange: (StreamSettings) -> Unit = {},
    val onVideoQualityChange: (width: Int, height: Int, fps: Int) -> Unit = { _, _, _ -> },
)
