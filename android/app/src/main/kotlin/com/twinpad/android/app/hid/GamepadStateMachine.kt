package com.twinpad.android.app.hid

import com.twinpad.android.app.wire.InputReport
import com.twinpad.android.app.wire.ProtocolConstants

/**
 * Estados del gamepad (lado app) — SPEC-004 §6.
 */
enum class GamepadState {
    /** Sin canal operativo: los controles no envían (H0). */
    OFFLINE,

    /** Canal operativo: los cambios generan `input_report` (H1). */
    READY,
}

/**
 * Máquina del gamepad (SPEC-004 §6/§11): mantiene la instantánea completa
 * de los elementos, asigna `seq` estrictamente creciente (invariante §14)
 * y solo emite un reporte cuando algo cambió (H1: cambio → reporte).
 */
class GamepadStateMachine {
    var state: GamepadState = GamepadState.OFFLINE
        private set

    private var nextSeq: Int = 0
    private var lastSent: InputReport? = null

    /** Activa el canal (sesión establecida): emite el reporte inicial en reposo (§6 H0→H1). */
    fun onChannelReady(): InputReport? {
        state = GamepadState.READY
        nextSeq = 0
        return buildAndTrack(restReport(0))
    }

    /** Canal caído: vuelve a H0 y los controles dejan de enviar (§6 H1→H0). */
    fun onChannelDown() {
        state = GamepadState.OFFLINE
        nextSeq = 0
        lastSent = null
    }

    /** Cambia elementos y devuelve el reporte a enviar, o `null` si nada
     * cambió (§10). El `seq` del snapshot entrante se ignora: lo asigna
     * esta máquina (R2). */
    fun onElementChanged(snapshot: InputReport): InputReport? {
        if (state != GamepadState.READY) return null // H0: no envía
        val previous = lastSent ?: restReport(0)
        val updated =
            InputReport(
                seq = nextSeq,
                stickLx = snapshot.stickLx.coerceIn(ProtocolConstants.STICK_MIN, ProtocolConstants.STICK_MAX),
                stickLy = snapshot.stickLy.coerceIn(ProtocolConstants.STICK_MIN, ProtocolConstants.STICK_MAX),
                stickRx = snapshot.stickRx.coerceIn(ProtocolConstants.STICK_MIN, ProtocolConstants.STICK_MAX),
                stickRy = snapshot.stickRy.coerceIn(ProtocolConstants.STICK_MIN, ProtocolConstants.STICK_MAX),
                dpad = snapshot.dpad.coerceIn(0, ProtocolConstants.DPAD_NEUTRAL),
                buttons = snapshot.buttons and BUTTONS_MASK,
                triggerL2 = snapshot.triggerL2.coerceIn(0, ProtocolConstants.TRIGGER_MAX),
                triggerR2 = snapshot.triggerR2.coerceIn(0, ProtocolConstants.TRIGGER_MAX),
                motion =
                    snapshot.motion?.takeIf {
                        it.accel.size == ProtocolConstants.GYRO_AXES && it.gyro.size == ProtocolConstants.GYRO_AXES
                    },
                touchX = snapshot.touchX.coerceIn(0, ProtocolConstants.TOUCH_MAX),
                touchY = snapshot.touchY.coerceIn(0, ProtocolConstants.TOUCH_MAX),
                touchPressed = snapshot.touchPressed,
            )
        if (unchanged(updated, previous)) return null // §10: sin cambio no hay reporte
        return buildAndTrack(updated)
    }

    private fun buildAndTrack(report: InputReport): InputReport {
        lastSent = report
        nextSeq = report.seq + 1
        return report
    }

    private fun restReport(seq: Int): InputReport =
        InputReport(
            seq = seq,
            stickLx = 0,
            stickLy = 0,
            stickRx = 0,
            stickRy = 0,
            dpad = ProtocolConstants.DPAD_NEUTRAL,
            buttons = 0,
            triggerL2 = 0,
            triggerR2 = 0,
            touchX = 0,
            touchY = 0,
            touchPressed = false,
        )

    private fun unchanged(
        a: InputReport,
        b: InputReport,
    ): Boolean =
        a.stickLx == b.stickLx && a.stickLy == b.stickLy &&
            a.stickRx == b.stickRx && a.stickRy == b.stickRy &&
            a.dpad == b.dpad && a.buttons == b.buttons &&
            a.triggerL2 == b.triggerL2 && a.triggerR2 == b.triggerR2 &&
            a.motion == b.motion && a.touchX == b.touchX && a.touchY == b.touchY &&
            a.touchPressed == b.touchPressed

    private companion object {
        /** Máscara de los 12 bits de botones (SPEC-004 §4). */
        const val BUTTONS_MASK: Int = (1 shl ProtocolConstants.BUTTON_COUNT) - 1
    }
}
