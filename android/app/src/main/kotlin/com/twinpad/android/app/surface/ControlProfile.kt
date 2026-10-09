package com.twinpad.android.app.surface

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Perfil de controles táctiles configurables — SPEC-008 §2/§4 (Draft, ENG
 * P10: modelo puro, testeable en JVM); también carga Config externa
 * `twinpad-config-v1` de SPEC-007 §4 (Draft) con la misma validación:
 * ≤ 24 controles, coords normalizadas 0…1, y `hid` restringido a los
 * Elementos HID canónicos de SPEC-004 §2 (R1/R4: nada envía sin asignación
 * válida).
 */
@Serializable
data class TouchControl(
    /** Identificador único (1–32 chars, SPEC-007 §4). */
    val id: String,
    /** `button` | `dpad` | `stick` (SPEC-007 §4; único discriminante v1). */
    val kind: String,
    /** Etiqueta visible (1–16 chars). */
    val label: String,
    /** Elemento HID canónico de SPEC-004 §2 al que envía; cadena vacía =
     * sin asignación (§8.1 de SPEC-008: se muestra atenuado y no envía). */
    val hid: String,
    /** Posición en coordenadas normalizadas 0…1 (SPEC-008 §4). */
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

@Serializable
data class ControlProfile(
    /** Juego asociado (id legible 1–64, SPEC-007 §4); vacío = default. */
    val game: String,
    val controls: List<TouchControl>,
)

/** Errores de validación (SPEC-008 §9 / SPEC-007 §9). */
enum class ProfileError {
    MALFORMED,
    FIELD,
    TOO_MANY_CONTROLS,
    DUPLICATE_ID,
    INVALID_HID,
    OUT_OF_RANGE,
}

/** Excepción de perfil/config inválida (SPEC-007 §9: rechazo con detalle). */
class ProfileException(
    val reason: ProfileError,
    detail: String,
) : Exception(detail)

/**
 * Validación y persistencia del perfil: objeto puro (ENG P10). El storage
 * es el JSON canónico; el codec acepta además `twinpad-config-v1` (SPEC-007)
 * normalizando la config a perfil (§8.2 de SPEC-008: misma validación).
 */
object ControlProfileCodec {
    /** Límites de SPEC-008 §4 / SPEC-007 §4. */
    const val MAX_CONTROLS: Int = 24
    private const val ID_MAX_CHARS: Int = 32
    private const val LABEL_MAX_CHARS: Int = 16
    private const val GAME_MAX_CHARS: Int = 64
    private const val MIN_COORD: Float = 0f
    private const val MAX_COORD: Float = 1f
    private const val MIN_SIZE: Float = 0.05f

    /** Elementos HID canónicos de SPEC-004 §2 (R4: no se inventan). */
    val HID_ELEMENTS: Set<String> =
        setOf(
            "dpad",
            "button_a", "button_b", "button_x", "button_y",
            "trigger_l2", "trigger_r2",
            "stick_l", "stick_r",
        )

    private val json = Json { encodeDefaults = true }

    /** Formato de Config externa (SPEC-007 §4). */
    const val CONFIG_FORMAT: String = "twinpad-config-v1"

    internal const val KIND_BUTTON: String = "button"
    internal const val KIND_DPAD: String = "dpad"
    internal const val KIND_STICK: String = "stick"
    private val KINDS: Set<String> = setOf(KIND_BUTTON, KIND_DPAD, KIND_STICK)
    internal const val DEFAULT_SIZE: Float = 0.16f
    private const val GRID_COLS: Int = 5
    private const val GRID_ROWS: Int = 5
    private const val GRID_X0: Float = 0.05f
    private const val GRID_Y0: Float = 0.10f
    private const val GRID_X_STEP: Float = 0.19f
    private const val GRID_Y_STEP: Float = 0.16f

    /** Posición X de la grilla de dispersión (col). */
    private fun gridX(col: Int): Float = GRID_X0 + col * GRID_X_STEP

    /** Posición Y de la grilla de dispersión (fila). */
    private fun gridY(row: Int): Float = GRID_Y0 + row * GRID_Y_STEP

    /**
     * Valida el perfil completo; el primer incumplimiento lanza
     * [ProfileException] con el detalle (SPEC-007 §9: rechazo con detalle).
     */
    fun validate(profile: ControlProfile) {
        validateHeader(profile.game, profile.controls.size)
        val seen = mutableSetOf<String>()
        for (c in profile.controls) {
            validateControl(c, seen)
        }
    }

    /** Encabezado del perfil (§4: game legible, ≤ 24 controles). */
    private fun validateHeader(
        game: String,
        nControls: Int,
    ) {
        if (game.length > GAME_MAX_CHARS) {
            throw ProfileException(ProfileError.FIELD, "game > $GAME_MAX_CHARS chars")
        }
        if (nControls > MAX_CONTROLS) {
            throw ProfileException(
                ProfileError.TOO_MANY_CONTROLS,
                "más de $MAX_CONTROLS controles ($nControls)",
            )
        }
    }

    /** Valida un control particular contra la lista en curso. */
    private fun validateControl(
        c: TouchControl,
        seen: MutableSet<String>,
    ) {
        validateTextFields(c)
        if (!seen.add(c.id)) {
            throw ProfileException(ProfileError.DUPLICATE_ID, "id duplicado: '${c.id}'")
        }
        validateHid(c)
        validateCoords(c)
    }

    /** id/label/kind válidos (SPEC-007 §4). */
    private fun validateTextFields(c: TouchControl) {
        if (c.id.isEmpty() || c.id.length > ID_MAX_CHARS) {
            throw ProfileException(ProfileError.FIELD, "id inválido: '${c.id}'")
        }
        if (c.label.isEmpty() || c.label.length > LABEL_MAX_CHARS) {
            throw ProfileException(ProfileError.FIELD, "label inválido en '${c.id}'")
        }
        if (c.kind !in KINDS) {
            throw ProfileException(ProfileError.FIELD, "kind inválido en '${c.id}': ${c.kind}")
        }
    }

    /** R1 reforzada: stick→stick_l/r, dpad→dpad, button→un botón/trigger. */
    private fun validateHid(c: TouchControl) {
        if (c.hid.isEmpty()) return // §8.1: sin asignación es válido (atenuado)
        if (c.hid !in HID_ELEMENTS) {
            throw ProfileException(
                ProfileError.INVALID_HID,
                "hid '${c.hid}' no es un Elemento HID válido (control '${c.id}')",
            )
        }
        val stickPair = setOf("stick_l", "stick_r")
        when (c.kind) {
            KIND_STICK ->
                if (c.hid !in stickPair) {
                    throw ProfileException(ProfileError.INVALID_HID, "stick '${c.id}' debe asignar a stick_l/stick_r")
                }
            KIND_DPAD ->
                if (c.hid != KIND_DPAD) {
                    throw ProfileException(ProfileError.INVALID_HID, "dpad '${c.id}' debe asignar a dpad")
                }
            else ->
                if (c.hid in stickPair || c.hid == KIND_DPAD) {
                    throw ProfileException(ProfileError.INVALID_HID, "button '${c.id}' no puede asignar a ${c.hid}")
                }
        }
    }

    /** Coordenadas normalizadas 0…1 con tamaño mínimo (SPEC-008 §4). */
    private fun validateCoords(c: TouchControl) {
        val originOk = inUnitRange(c.x) && inUnitRange(c.y)
        val sizeOk = c.w >= MIN_SIZE && c.h >= MIN_SIZE
        val inside = c.x + c.w <= MAX_COORD + EPS && c.y + c.h <= MAX_COORD + EPS
        if (!(originOk && sizeOk && inside)) {
            throw ProfileException(
                ProfileError.OUT_OF_RANGE,
                "coordenadas fuera de 0…1 en '${c.id}' (x=${c.x},y=${c.y},w=${c.w},h=${c.h})",
            )
        }
    }

    /** Valor dentro de 0…1 (inclusive). */
    private fun inUnitRange(v: Float): Boolean = v >= MIN_COORD && v <= MAX_COORD

    private const val EPS: Float = 1e-4f

    /** Serializa el perfil (tras validar) al JSON canónico. */
    fun encode(profile: ControlProfile): String {
        validate(profile)
        return json.encodeToString(ControlProfile.serializer(), profile)
    }

    /** Decodifica JSON de perfil; error de forma → [ProfileException] MALFORMED. */
    fun decode(text: String): ControlProfile {
        val profile =
            try {
                json.decodeFromString(ControlProfile.serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw ProfileException(ProfileError.MALFORMED, "JSON de perfil malformado")
            }
        validate(profile)
        return profile
    }

    /**
     * Decodifica una Config externa `twinpad-config-v1` (SPEC-007 §4) y la
     * normaliza a [ControlProfile]; posiciones ausentes se auto-dispersan
     * (el editor permite reacomodar). Rechazo con detalle si es inválida.
     */
    fun decodeExternalConfig(
        text: String,
        gameOverride: String,
    ): ControlProfile {
        val obj = parseRootObject(text)
        validateFormat(obj)
        val game =
            (obj["game"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: throw ProfileException(ProfileError.FIELD, "game faltante")
        val arr =
            obj["controls"] as? kotlinx.serialization.json.JsonArray
                ?: throw ProfileException(ProfileError.FIELD, "controls faltante")
        return ControlProfile(gameOverride.ifEmpty { game }, arr.mapIndexed(::controlAt))
    }

    private fun parseRootObject(text: String): kotlinx.serialization.json.JsonObject =
        try {
            json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                ?: throw ProfileException(ProfileError.MALFORMED, "la raíz debe ser un objeto")
        } catch (e: IllegalArgumentException) {
            throw ProfileException(ProfileError.MALFORMED, "JSON malformado")
        }

    private fun validateFormat(obj: kotlinx.serialization.json.JsonObject) {
        val format = (obj["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (format != CONFIG_FORMAT) {
            throw ProfileException(
                ProfileError.FIELD,
                "format '$format' != \"$CONFIG_FORMAT\"",
            )
        }
    }

    /** Un control del array externo; posiciones por grilla de dispersión. */
    private fun controlAt(
        i: Int,
        el: kotlinx.serialization.json.JsonElement,
    ): TouchControl {
        val c =
            el as? kotlinx.serialization.json.JsonObject
                ?: throw ProfileException(ProfileError.FIELD, "control[$i] no es objeto")
        val id = c.stringField("id") ?: throw ProfileException(ProfileError.FIELD, "control[$i].id")
        return TouchControl(
            id = id,
            kind = c.stringField("kind") ?: throw ProfileException(ProfileError.FIELD, "control[$i].kind"),
            label = c.stringField("label") ?: throw ProfileException(ProfileError.FIELD, "control[$i].label"),
            hid = c.stringField("hid") ?: "",
            x = gridX(i % GRID_COLS),
            y = gridY((i / GRID_COLS) % GRID_ROWS),
            w = DEFAULT_SIZE,
            h = DEFAULT_SIZE,
        )
    }

    private fun kotlinx.serialization.json.JsonObject.stringField(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    /** Perfil default (superficie genérica, SPEC-008 §10). */
    fun defaultProfile(game: String = ""): ControlProfile = ControlProfile(game, defaultControls())
}

/** Coordenadas del default (SPEC-008 §10; layout del gamepad genérico). */
private const val DEF_STICK_X = 0.04f
private const val DEF_STICK_W = 0.22f
private const val DEF_STICK_H = 0.32f
private const val DEF_STICK_Y = 0.62f
private const val DEF_BTN_SIZE = 0.10f
private const val DEF_BTN_H = 0.14f
private const val DEF_A_Y = 0.44f
private const val DEF_B_Y = 0.36f
private const val DEF_B_X = 0.82f
private const val DEF_X_X = 0.58f
private const val DEF_Y_Y = 0.28f
private const val DEF_DPAD_Y = 0.30f
private const val DEF_TRIGGER_SIZE = 0.14f
private const val DEF_TRIGGER_H = 0.12f
private const val DEF_TRIGGER_Y = 0.08f
private const val DEF_TRIGGER_R_X = 0.80f

/** X de la columna derecha y alto del d-pad (default, SPEC-008 §10). */
private const val RIGHT_COLUMN_X = 0.74f
private const val DEF_DPAD_H = 0.30f

/** Offset del botón A/Y respecto a la columna derecha (default). */
private const val BTN_A_OFFSET_X = 0.04f

/** Controles de la superficie por omisión (SPEC-008 §10: gamepad genérico). */
private fun defaultControls(): List<TouchControl> =
    listOf(
        stickDefault("stick_left", "L", "stick_l", DEF_STICK_X, DEF_STICK_Y),
        stickDefault("stick_right", "R", "stick_r", RIGHT_COLUMN_X, DEF_STICK_Y),
        faceDefault("btn_a", "A", "button_a", RIGHT_COLUMN_X - BTN_A_OFFSET_X, DEF_A_Y),
        faceDefault("btn_b", "B", "button_b", DEF_B_X, DEF_B_Y),
        faceDefault("btn_x", "X", "button_x", DEF_X_X, DEF_B_Y),
        faceDefault("btn_y", "Y", "button_y", RIGHT_COLUMN_X - BTN_A_OFFSET_X, DEF_Y_Y),
        dpadDefault(DEF_STICK_X, DEF_DPAD_Y),
        triggerDefault("trigger_l", "L2", "trigger_l2", DEF_STICK_X),
        triggerDefault("trigger_r", "R2", "trigger_r2", DEF_TRIGGER_R_X),
    )

private fun stickDefault(
    id: String,
    label: String,
    hid: String,
    x: Float,
    y: Float,
): TouchControl =
    TouchControl(
        id,
        ControlProfileCodec.KIND_STICK,
        label,
        hid,
        x,
        y,
        DEF_STICK_W,
        DEF_STICK_H,
    )

private fun faceDefault(
    id: String,
    label: String,
    hid: String,
    x: Float,
    y: Float,
): TouchControl =
    TouchControl(
        id,
        ControlProfileCodec.KIND_BUTTON,
        label,
        hid,
        x,
        y,
        DEF_BTN_SIZE,
        DEF_BTN_H,
    )

private fun dpadDefault(
    x: Float,
    y: Float,
): TouchControl =
    TouchControl(
        "dpad_cross",
        ControlProfileCodec.KIND_DPAD,
        "D-pad",
        "dpad",
        x,
        y,
        DEF_STICK_W,
        DEF_DPAD_H,
    )

private fun triggerDefault(
    id: String,
    label: String,
    hid: String,
    x: Float,
): TouchControl =
    TouchControl(
        id,
        ControlProfileCodec.KIND_BUTTON,
        label,
        hid,
        x,
        DEF_TRIGGER_Y,
        DEF_TRIGGER_SIZE,
        DEF_TRIGGER_H,
    )
