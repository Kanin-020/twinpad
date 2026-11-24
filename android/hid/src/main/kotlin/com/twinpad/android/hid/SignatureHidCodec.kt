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
 * byte 3: stick R X (int8, −127…127)          — usage Rx
 * byte 4: stick R Y (int8, −127…127)          — usage Ry
 * byte 5: trigger L2 (uint8, 0…255)           — usage Z
 * byte 6: trigger R2 (uint8, 0…255)           — usage Rz
 * byte 7: hat 4 bits (0…7 dirección, 0x0F
 *         neutral vía Null State) + botones
 *         h0…h3 en bits 4…7
 * byte 8: botones h4…h11 (bits 0…7)
 * byte 9: botones h12…h14 (bits 0…2) + pad (bits 3…7)
 * byte 10: batería del teléfono, porcentaje 0…100 (CR-110, Battery Strength)
 *
 * Los botones van en el orden de usages HID 1…15 (bit n = Usage n+1),
 * que es el orden que el SO y Steam esperan de un gamepad estándar:
 * A=1, B=2, X=4, Y=5, L1=7, R1=8, L3=14, R3=15, Select=11, Start=12 y
 * los dos extra en 3 y 6. Los ejes siguen la disposición Xbox
 * (X/Y izq, Rx/Ry der, Z/Rz gatillos): con Z/Rz en el stick derecho el SO
 * los tomaba por gatillos y viceversa.
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

    /** Máximo del porcentaje de batería que declara el report map (CR-110). */
    private const val BATTERY_MAX = 100

    /** 0 = D-pad activo mínimo, 8 = neutro de la Signature (SPEC-004 §4). */
    private const val DPAD_NEUTRAL = 8

    /** Valor del hat para el neutro: el Null State consume 8…15. */
    private const val HAT_NEUTRAL = 0x0F

    /** Nibble bajo del byte 7: hat. */
    private const val HAT_MASK = 0x0F

    /** Bits por nibble del byte 7 (hat + h0…h3). */
    private const val NIBBLE_BITS = 4

    /** Bits de botón que caben en el byte 8. */
    private const val BYTE_BITS = 8

    /** Máscara de un byte (0xFF) para leer valores sin signo. */
    private const val BYTE_MASK = 0xFF

    /** Byte del hat + nibble bajo de botones (h0…h3 en bits 4…7). */
    private fun byte7(
        dpad: Int,
        hidButtons: Int,
    ): Int {
        val hat = if (dpad == DPAD_NEUTRAL) HAT_NEUTRAL else dpad
        return (hat and HAT_MASK) or ((hidButtons and HAT_MASK) shl NIBBLE_BITS)
    }

    /** Bit del reporte HID (Usage = bit + 1) que ocupa cada botón canónico. */
    private val HID_BIT_BY_CANONICAL =
        intArrayOf(
            // button_a → Usage 1 (Sur)
            0,
            // button_b → Usage 2 (Este)
            1,
            // button_x → Usage 4 (Norte)
            3,
            // button_y → Usage 5 (Oeste)
            4,
            // button_l1 → Usage 7 (TL)
            6,
            // button_r1 → Usage 8 (TR)
            7,
            // button_l3 → Usage 14 (ThumbL)
            13,
            // button_r3 → Usage 15 (ThumbR)
            14,
            // button_start → Usage 12 (Start)
            11,
            // button_select → Usage 11 (Select)
            10,
            // button_extra_1 → Usage 3 (C)
            2,
            // button_extra_2 → Usage 6 (Z)
            5,
        )

    /** Reordena la máscara canónica al orden de usages del report map. */
    private fun hidButtons(buttons: Int): Int {
        var hid = 0
        for (canonical in HID_BIT_BY_CANONICAL.indices) {
            if (buttons and (1 shl canonical) != 0) hid = hid or (1 shl HID_BIT_BY_CANONICAL[canonical])
        }
        return hid
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
        batteryPercent: Int = 0,
    ): ByteArray {
        val hid = hidButtons(buttons)
        return byteArrayOf(
            REPORT_ID,
            stickLx.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            stickLy.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            stickRx.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            stickRy.coerceIn(STICK_MIN, STICK_MAX).toByte(),
            triggerL2.coerceIn(0, TRIGGER_MAX).toByte(),
            triggerR2.coerceIn(0, TRIGGER_MAX).toByte(),
            byte7(dpad.coerceIn(0, DPAD_NEUTRAL), hid).toByte(),
            ((hid shr NIBBLE_BITS) and BYTE_MASK).toByte(),
            ((hid shr (NIBBLE_BITS + BYTE_BITS)) and HAT_MASK).toByte(),
            batteryPercent.coerceIn(0, BATTERY_MAX).toByte(),
        )
    }

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
            bytes(0x09, 0x33, 0x81, 0x02) + // RX (usage Rx, disposición Xbox)
            bytes(0x15, 0x81, 0x25, 0x7F) +
            bytes(0x09, 0x34, 0x81, 0x02) + // RY (usage Ry)
            bytes(0x15, 0x00, 0x26, 0xFF, 0x00, 0x09, 0x32, 0x81, 0x02) + // L2 (usage Z)
            bytes(0x09, 0x35, 0x81, 0x02) + // R2 (usage Rz)
            bytes(0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01) + // hat phys 0…315
            bytes(0x75, 0x04, 0x95, 0x01, 0x09, 0x39) +
            bytes(0x81, 0x42) + // Input Data Var Abs Null state
            bytes(0x15, 0x00, 0x25, 0x01, 0x05, 0x09, 0x19, 0x01, 0x29, 0x0F) + // Botones 1…15
            bytes(0x75, 0x01, 0x95, 0x0F, 0x81, 0x02) +
            bytes(0x75, 0x01, 0x95, 0x05, 0x81, 0x03) + // pad 5 bits const
            bytes(0x05, 0x06, 0x09, 0x20, 0x15, 0x00, 0x25, 0x64) + // Battery Strength 0…100 (CR-110)
            bytes(0x75, 0x08, 0x95, 0x01, 0x81, 0x02) +
            bytes(0xC0) + // End Collection (report 1)
            // Report ID 3 (CR-109, vibración): salida strong/weak uint8.
            bytes(0x05, 0x0F, 0x09, 0x21, 0xA1, 0x02, 0x85, 0x03) +
            bytes(0x09, 0x97, 0x15, 0x00, 0x25, 0x01, 0x75, 0x04, 0x95, 0x01, 0x91, 0x02) +
            bytes(0x09, 0x70, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x91, 0x02) +
            bytes(0xC0)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { i -> values[i].toByte() }
}
