package com.twinpad.android.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Opciones de resolución del sidebar (16:9; CR-001). */
internal val RESOLUTION_OPTIONS =
    listOf(
        "960×540" to (960 to 540),
        "1280×720" to (1280 to 720),
        "1920×1080" to (1920 to 1080),
    )

/** Opciones de fps del sidebar. */
internal val FPS_OPTIONS = listOf(30, 60)

/** Opacidad del scrim del sidebar (detekt: sin números mágicos). */
private const val SCRIM_ALPHA: Float = 0.45f

/** Ancho del panel del sidebar desde el borde izquierdo (dp). */
private const val SIDEBAR_WIDTH_DP: Int = 280

/**
 * Overlay del sidebar: scrim a pantalla completa + panel lateral izquierdo.
 * Se pidió como menú lateral (no un diálogo centrado); el scrim cierra al
 * tocarlo.
 */
@Composable
internal fun SettingsOverlay(
    settings: StreamSettings,
    onDismiss: () -> Unit,
    onSettingsChange: (StreamSettings) -> Unit,
    onVideoQualityChange: (Int, Int, Int) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .noRippleClick(onDismiss),
    ) {
        SettingsPanel(
            settings = settings,
            onDismiss = onDismiss,
            onSettingsChange = onSettingsChange,
            onVideoQualityChange = onVideoQualityChange,
        )
    }
}

/** Panel lateral con las secciones Resolución / FPS / HUD / Controles. */
@Composable
private fun SettingsPanel(
    settings: StreamSettings,
    onDismiss: () -> Unit,
    onSettingsChange: (StreamSettings) -> Unit,
    onVideoQualityChange: (Int, Int, Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(SIDEBAR_WIDTH_DP.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes de transmisión", style = MaterialTheme.typography.titleMedium)
        ResolutionSection(settings, onSettingsChange, onVideoQualityChange)
        FpsSection(settings, onSettingsChange, onVideoQualityChange)
        HudSection(settings, onSettingsChange)
        ControlsSection(settings, onSettingsChange)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Cerrar") }
    }
}

/** Sección de resolución: una fila por opción, con la vigente marcada. */
@Composable
private fun ResolutionSection(
    settings: StreamSettings,
    onSettingsChange: (StreamSettings) -> Unit,
    onVideoQualityChange: (Int, Int, Int) -> Unit,
) {
    Text("Resolución", style = MaterialTheme.typography.labelLarge)
    RESOLUTION_OPTIONS.forEach { (label, dims) ->
        val (w, h) = dims
        SelectionRow(
            label = label,
            selected = settings.width == w && settings.height == h,
            onSelect = {
                onVideoQualityChange(w, h, settings.fps)
                onSettingsChange(settings.copy(width = w, height = h))
            },
        )
    }
}

/** Sección de fps: una fila por opción, con la vigente marcada. */
@Composable
private fun FpsSection(
    settings: StreamSettings,
    onSettingsChange: (StreamSettings) -> Unit,
    onVideoQualityChange: (Int, Int, Int) -> Unit,
) {
    Text("FPS", style = MaterialTheme.typography.labelLarge)
    FPS_OPTIONS.forEach { fps ->
        SelectionRow(
            label = fps.toString(),
            selected = settings.fps == fps,
            onSelect = {
                onVideoQualityChange(settings.width, settings.height, fps)
                onSettingsChange(settings.copy(fps = fps))
            },
        )
    }
}

/** Sección HUD del sidebar. */
@Composable
private fun HudSection(
    settings: StreamSettings,
    onSettingsChange: (StreamSettings) -> Unit,
) {
    Text("HUD", style = MaterialTheme.typography.labelLarge)
    SwitchSetting(
        label = if (settings.showFps) "Ocultar fps" else "Mostrar fps",
        checked = settings.showFps,
        onToggle = { onSettingsChange(settings.copy(showFps = it)) },
    )
}

/** Sección Controles del sidebar. */
@Composable
private fun ControlsSection(
    settings: StreamSettings,
    onSettingsChange: (StreamSettings) -> Unit,
) {
    Text("Controles", style = MaterialTheme.typography.labelLarge)
    SwitchSetting(
        label = if (settings.showControls) "Ocultar botones" else "Mostrar botones",
        checked = settings.showControls,
        onToggle = { onSettingsChange(settings.copy(showControls = it)) },
    )
}

/** Fila seleccionable del sidebar con el marcador de la opción vigente. */
@Composable
private fun SelectionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            if (selected) {
                Text("●", color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.noRippleClick(onSelect),
    )
}

/** Fila de encendido/apagado dentro del sidebar. */
@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.noRippleClick { onToggle(!checked) },
    ) {
        Switch(checked = checked, onCheckedChange = onToggle)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Click sin ripple (la vista de sesión gestiona sus propios gestos). */
internal fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
    composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
