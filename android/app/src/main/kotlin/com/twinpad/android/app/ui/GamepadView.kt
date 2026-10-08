package com.twinpad.android.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants
import kotlin.math.roundToInt

/**
 * Superficie táctil del gamepad — SPEC-004 §7 paso 1 (la app actualiza el
 * estado del elemento al tocar). Signature HID de §2: D-pad de 4 direcciones,
 * botones A/B/X/Y, 2 sticks analógicos (−127…127, centro 0) y triggers
 * analógicos L2/R2 (0…255) como sliders. La superficie configurable completa
 * es REQ-008 / SPEC-008 (Draft).
 *
 * La lógica de mapeo vive en [GamepadSurfaceState] (pura, testeable en JVM);
 * este Composable solo alimenta sus eventos y entrega cada instantánea a
 * `onInput` — el `seq` lo asigna GamepadStateMachine (R2).
 */
@Composable
fun GamepadView(onInput: (InputReport) -> Unit) {
    val surface = remember { GamepadSurfaceState() }
    var buttons by remember { mutableStateOf(0) }
    var dpad by remember { mutableStateOf(DpadState.NEUTRAL) }
    var stickLx by remember { mutableFloatStateOf(0f) }
    var stickLy by remember { mutableFloatStateOf(0f) }
    var stickRx by remember { mutableFloatStateOf(0f) }
    var stickRy by remember { mutableFloatStateOf(0f) }
    var triggerL2 by remember { mutableFloatStateOf(0f) }
    var triggerR2 by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DpadRow(dpad = dpad, onDirection = {
            dpad = it
            surface.setDpad(it.value)
        })
        FaceButtonsRow(buttons = buttons, onButtons = {
            buttons = it
            surface.setButtons(it)
        })
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VirtualStick("L", stickLx, stickLy) { x, y ->
                stickLx = x
                stickLy = y
                surface.setStick(x, y, isLeft = true)
            }
            VirtualStick("R", stickRx, stickRy) { x, y ->
                stickRx = x
                stickRy = y
                surface.setStick(x, y, isLeft = false)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TriggerSlider("L2", triggerL2) {
                triggerL2 = it
                surface.setTrigger(it, isLeft = true)
            }
            TriggerSlider("R2", triggerR2) {
                triggerR2 = it
                surface.setTrigger(it, isLeft = false)
            }
        }
    }

    // §7 paso 2: cualquier cambio de la instantánea dispara el reporte.
    LaunchedEffect(buttons, dpad, stickLx, stickLy, stickRx, stickRy, triggerL2, triggerR2) {
        onInput(surface.snapshot())
    }
}

/** D-pad de 4 direcciones (§4: 0=norte… 7=noroeste, 8=neutro). */
@Composable
private fun DpadRow(
    dpad: DpadState,
    onDirection: (DpadState) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GamepadButton(
            label = "◀",
            active = dpad == DpadState(ProtocolConstants.DPAD_LEFT),
            onPress = { onDirection(DpadState(ProtocolConstants.DPAD_LEFT)) },
            onRelease = { onDirection(DpadState.NEUTRAL) },
        )
        GamepadButton(
            label = "▲",
            active = dpad == DpadState(ProtocolConstants.DPAD_UP),
            onPress = { onDirection(DpadState(ProtocolConstants.DPAD_UP)) },
            onRelease = { onDirection(DpadState.NEUTRAL) },
        )
        GamepadButton(
            label = "▼",
            active = dpad == DpadState(ProtocolConstants.DPAD_DOWN),
            onPress = { onDirection(DpadState(ProtocolConstants.DPAD_DOWN)) },
            onRelease = { onDirection(DpadState.NEUTRAL) },
        )
        GamepadButton(
            label = "▶",
            active = dpad == DpadState(ProtocolConstants.DPAD_RIGHT),
            onPress = { onDirection(DpadState(ProtocolConstants.DPAD_RIGHT)) },
            onRelease = { onDirection(DpadState.NEUTRAL) },
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

/** Diámetro de la base y del pulgar del stick virtual (dp). */
private val STICK_BASE_SIZE = 96.dp
private val STICK_THUMB_SIZE = 40.dp
private val STICK_THUMB_OFFSET = (STICK_BASE_SIZE - STICK_THUMB_SIZE) / 2

/**
 * Stick analógico virtual (SPEC-004 §2: rango −127…127, centro 0).
 * Arrastrar el pulgar mueve el eje; soltar lo centra (§10: el stick en el
 * centro exacto tras el movimiento reporta 0, no queda "pegado").
 * `onMove` recibe ejes normalizados −1…1.
 */
@Composable
private fun VirtualStick(
    label: String,
    normalizedX: Float,
    normalizedY: Float,
    onMove: (Float, Float) -> Unit,
) {
    val radiusPx =
        with(androidx.compose.ui.platform.LocalDensity.current) {
            (STICK_BASE_SIZE - STICK_THUMB_SIZE).toPx() / 2f
        }
    Box(
        modifier =
            Modifier
                .size(STICK_BASE_SIZE)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            onMove(0f, 0f) // §10: al soltar vuelve al centro
                        },
                        onDragCancel = {
                            onMove(0f, 0f)
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        val rawX = change.position.x - size.width / 2f
                        val rawY = change.position.y - size.height / 2f
                        val distance = kotlin.math.hypot(rawX.toDouble(), rawY.toDouble())
                        val clampedX: Float
                        val clampedY: Float
                        if (distance <= radiusPx || distance == 0.0) {
                            clampedX = rawX
                            clampedY = rawY
                        } else {
                            val scale = radiusPx / distance
                            clampedX = (rawX * scale).toFloat()
                            clampedY = (rawY * scale).toFloat()
                        }
                        onMove(clampedX / radiusPx, clampedY / radiusPx)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box(
            modifier =
                Modifier
                    .size(STICK_THUMB_SIZE)
                    .offset {
                        IntOffset(
                            (normalizedX * STICK_THUMB_OFFSET.toPx()).roundToInt(),
                            (normalizedY * STICK_THUMB_OFFSET.toPx()).roundToInt(),
                        )
                    }
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

/** Altura y ancho del slider del trigger (dp). */
private val TRIGGER_TRACK_HEIGHT = 48.dp
private val TRIGGER_TRACK_WIDTH = 96.dp

/**
 * Trigger analógico como slider vertical (SPEC-004 §2/§4: 0…255).
 * El arrastre dentro de la pista fija el valor 0…1 (abajo = 0, arriba = 1);
 * soltar vuelve al reposo. `onValue` recibe el valor normalizado.
 */
@Composable
private fun TriggerSlider(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .size(TRIGGER_TRACK_WIDTH, TRIGGER_TRACK_HEIGHT)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { onValue(0f) },
                            onDragCancel = { onValue(0f) },
                        ) { change, _ ->
                            change.consume()
                            val fraction =
                                (1f - change.position.y / size.height)
                                    .coerceIn(0f, 1f)
                            onValue(fraction)
                        }
                    },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Nivel de llenado proporcional al valor (feedback visual).
            Box(
                modifier =
                    Modifier
                        .size(TRIGGER_TRACK_WIDTH, (TRIGGER_TRACK_HEIGHT * value))
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
