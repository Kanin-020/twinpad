package com.twinpad.android.hid

/**
 * Codec de la Signature HID → Report Map HID de Bluetooth (CR-059, ADR-009,
 * SPEC-004 §2/§4).
 *
 * Report ID 1 — gamepad estándar (mismo orden de campos que el report map:
 * ejes → triggers analógicos → hat/botones → pad), estilo DS4 para que
 * Windows lo mapee a XInput sin software adicional:
 *
 * ```
 * byte 1: stick L X (int8, −127…127)          — usage X
 * byte 2: stick L Y (int8, −127…127)          — usage Y
 * byte 3: stick R X (int8, −127…127)          — usage Z
 * byte 4: stick R Y (int8, −127…127)          — usage Rz
 * byte 5: trigger L2 (uint8, 0…255)           — usage Rx
 * byte 6: trigger R2 (uint8, 0…255)           — usage Ry
 * byte 7: hat 4 bits (0…7 dirección, 0x0F
 *         neutral vía Null State) + botones
 *         b0…b3 en bits 4…7
 * byte 8: botones b4…b11 (bits 0…7)
 * byte 9: pad const bits 0…3 (0)
 * ```
 *
 * La Signature usa 8 como neutro del D-pad: se mapea a 0x0F.
 *
 * Touch DRC, muestras inerciales y rumble NO viajan por esta ruta
 * (ADR-009 §5: quedan exclusivos de la ruta LAN/SDK).
 */
object SignatureHidCodec {
    /** Report ID que declara el report map. */
    private const val REPORT_ID: Byte = 1

    private const val STICK_MIN = -127
    private const val STICK_MAX = 127
    private const val TRIGGER_MAX = 255

    /** 0 = D-pad activo mínimo, 8 = neutro de la Signature (SPEC-004 §4). */
    private const val DPAD_NEUTRAL = 8

    /** Valor del hat para el neutro: el Null State consume 8…15. */
    private const val HAT_NEUTRAL = 0x0F

    /** Nibble alto del byte 7: botones b0…b3. */
    private const val BUTTONS_LOW_NIBBLE = 0x0F

    /** Bits por nibble del byte 7 (hat + b0…b3). */
    private const val NIBBLE_BITS = 4

    /** Byte del hat + nibble bajo de botones (b0…b3 en bits 4…7). */
    private fun byte7(
        dpad: Int,
        buttons: Int,
    ): Int {
        val hat = if (dpad == DPAD_NEUTRAL) HAT_NEUTRAL else dpad
        return (hat and HAT_NEUTRAL) or ((buttons and BUTTONS_LOW_NIBBLE) shl NIBBLE_BITS)
    }

    // La firma refleja el contrato FIJO de la Signature (SPEC-004 §2: 2
    // sticks, D-pad, botones, 2 triggers); alterarla oculta el contrato.
    //
    // Codifica el estado de la Signature (campos crudos del wire, SPEC-004
    // §4) al reporte de input del gamepad BT. Los valores fuera de rango se
    // clampean a los límites (SPEC-004 §9: sin error, elemento al límite).
    @Suppress("LongParameterList")
    fun encode(
        stickLx: Int,
        stickLy: Int,
        stickRx: Int,
        stickRy: Int,
        dpad: Int,
        buttons: Int,
        triggerL2: Int,
        triggerR2: Int,
    ): ByteArray =
        byteArrayOf(
            REPORT_ID,
            stickLx.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            stickLy.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            stickRx.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            stickRy.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            triggerL2.coerceIn(0, TRIGGER_MAX).toByte(),
            triggerR2.coerceIn(0, TRIGGER_MAX).toByte(),
            byte7(dpad.coerceIn(0, DPAD_NEUTRAL), buttons).toByte(),
            ((buttons shr NIBBLE_BITS) and BUTTONS_HIGH_MASK).toByte(),
            PAD_BYTE,
        )

    /**
     * Descriptor HID (report map, bytes crudos para `BluetoothHidDevice
     * .registerApp`): gamepad estándar estilo DS4 con Report ID 1 — esto lo
     * consume Windows tal cual.
     */
    val REPORT_MAP: ByteArray =
        bytes(0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01) + // Usage Gamepad, App coll, ID 1
            bytes(0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01) + // LX −127…127
            bytes(0x09, 0x30, 0x81, 0x02) +
            bytes(0x15, 0x81, 0x25, 0x7F) +
            bytes(0x09, 0x31, 0x81, 0x02) + // LY
            bytes(0x15, 0x81, 0x25, 0x7F) +
            bytes(0x09, 0x32, 0x81, 0x02) + // RX (usage Z, estilo DS4)
            bytes(0x15, 0x81, 0x25, 0x7F) +
            bytes(0x09, 0x35, 0x81, 0x02) + // RY (usage Rz, estilo DS4)
            bytes(0x15, 0x00, 0x26, 0xFF, 0x00, 0x09, 0x33, 0x81, 0x02) + // L2 (usage Rx)
            bytes(0x09, 0x34, 0x81, 0x02) + // R2 (usage Ry)
            bytes(0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01) + // hat phys 0…315
            bytes(0x75, 0x04, 0x95, 0x01, 0x09, 0x39) +
            bytes(0x81, 0x42) + // Input Data Var Abs Null state
            bytes(0x15, 0x00, 0x25, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0C) + // Botones 1…12
            bytes(0x75, 0x01, 0x95, 0x0C, 0x81, 0x02) +
            bytes(0x75, 0x01, 0x95, 0x04, 0x81, 0x03) + // pad 4 bits const
            bytes(0xC0) // End Collection

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { i -> values[i].toByte() }

    /** Nibble alto del byte 7: botones b0…b3 (reutilizado para mascaras). */
    private const val BUTTONS_HIGH_MASK = 0xFF

    /** Byte 9: pad const bits 0…3 (el nibble alto queda sin declarar). */
    private const val PAD_BYTE = 0x00.toByte()
}
