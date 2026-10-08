package com.twinpad.android.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants

/**
 * Superficie táctil del gamepad — SPEC-004 §7 paso 1 (la app actualiza el
 * estado del elemento al tocar). Versión inicial: botones A/B/X/Y y D-pad
 * de 4 direcciones (la superficie configurable completa es REQ-008 /
 * SPEC-008, aún Draft).
 *
 * Cada botón presionado emite un reporte con su bit encendido; al soltarlo
 * emite el reporte con el bit apagado (§7 paso 2: instantánea completa con
 * `seq` mayor; el server ve presionar/soltar).
 */
@Composable
fun GamepadView(onInput: (InputReport) -> Unit) {
    var buttons by remember { mutableStateOf(0) }
    var dpad by remember { mutableStateOf(ProtocolConstants.DPAD_NEUTRAL) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DpadRow(dpad = dpad, onDirection = { dpad = it })
        FaceButtonsRow(buttons = buttons, onButtons = { buttons = it })
    }

    // §7 paso 2: cualquier cambio de la instantánea dispara el reporte.
    // El `seq` lo asigna GamepadStateMachine (R2).
    LaunchedEffect(buttons, dpad) {
        onInput(
            InputReport(
                seq = 0,
                stickLx = 0,
                stickLy = 0,
                stickRx = 0,
                stickRy = 0,
                dpad = dpad,
                buttons = buttons,
                triggerL2 = 0,
                triggerR2 = 0,
            ),
        )
    }
}

/** D-pad de 4 direcciones (§4: 0=norte… 7=noroeste, 8=neutro). */
@Composable
private fun DpadRow(
    dpad: Int,
    onDirection: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GamepadButton(
            label = "◀",
            active = dpad == ProtocolConstants.DPAD_LEFT,
            onPress = { onDirection(ProtocolConstants.DPAD_LEFT) },
            onRelease = { onDirection(ProtocolConstants.DPAD_NEUTRAL) },
        )
        GamepadButton(
            label = "▲",
            active = dpad == ProtocolConstants.DPAD_UP,
            onPress = { onDirection(ProtocolConstants.DPAD_UP) },
            onRelease = { onDirection(ProtocolConstants.DPAD_NEUTRAL) },
        )
        GamepadButton(
            label = "▼",
            active = dpad == ProtocolConstants.DPAD_DOWN,
            onPress = { onDirection(ProtocolConstants.DPAD_DOWN) },
            onRelease = { onDirection(ProtocolConstants.DPAD_NEUTRAL) },
        )
        GamepadButton(
            label = "▶",
            active = dpad == ProtocolConstants.DPAD_RIGHT,
            onPress = { onDirection(ProtocolConstants.DPAD_RIGHT) },
            onRelease = { onDirection(ProtocolConstants.DPAD_NEUTRAL) },
        )
    }
}

/** Botones A/B/X/Y (§4: bits 0–3 de la máscara). */
@Composable
private fun FaceButtonsRow(
    buttons: Int,
    onButtons: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GamepadButton(
            label = "A",
            active = buttons and ProtocolConstants.BUTTON_BIT_A != 0,
            onPress = { onButtons(buttons or ProtocolConstants.BUTTON_BIT_A) },
            onRelease = { onButtons(buttons and ProtocolConstants.BUTTON_BIT_A.inv()) },
        )
        GamepadButton(
            label = "B",
            active = buttons and ProtocolConstants.BUTTON_BIT_B != 0,
            onPress = { onButtons(buttons or ProtocolConstants.BUTTON_BIT_B) },
            onRelease = { onButtons(buttons and ProtocolConstants.BUTTON_BIT_B.inv()) },
        )
        GamepadButton(
            label = "X",
            active = buttons and ProtocolConstants.BUTTON_BIT_X != 0,
            onPress = { onButtons(buttons or ProtocolConstants.BUTTON_BIT_X) },
            onRelease = { onButtons(buttons and ProtocolConstants.BUTTON_BIT_X.inv()) },
        )
        GamepadButton(
            label = "Y",
            active = buttons and ProtocolConstants.BUTTON_BIT_Y != 0,
            onPress = { onButtons(buttons or ProtocolConstants.BUTTON_BIT_Y) },
            onRelease = { onButtons(buttons and ProtocolConstants.BUTTON_BIT_Y.inv()) },
        )
    }
}

/** Botón táctil que reporta presionar y soltar (§7: el estado final viaja). */
@Composable
private fun GamepadButton(
    label: String,
    active: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    LaunchedEffect(isPressed) {
        if (isPressed) onPress() else onRelease()
    }
    Button(onClick = {}, interactionSource = interaction) {
        Text(if (active) "[$label]" else label)
    }
}
