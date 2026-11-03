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
            "button_l1", "button_r1", "button_l3", "button_r3",
            "button_start", "button_select", "button_extra_1", "button_extra_2",
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
        for (control in profile.controls) {
            validateControl(control, seen)
        }
    }

    /** Encabezado del perfil (§4: game legible, ≤ 24 controles). */
    private fun validateHeader(
        game: String,
        controlCount: Int,
    ) {
        if (game.length > GAME_MAX_CHARS) {
            throw ProfileException(ProfileError.FIELD, "game > $GAME_MAX_CHARS chars")
        }
        if (controlCount > MAX_CONTROLS) {
            throw ProfileException(
                ProfileError.TOO_MANY_CONTROLS,
                "más de $MAX_CONTROLS controles ($controlCount)",
            )
        }
    }

    /** Valida un control particular contra la lista en curso. */
    private fun validateControl(
        control: TouchControl,
        seen: MutableSet<String>,
    ) {
        validateTextFields(control)
        if (!seen.add(control.id)) {
            throw ProfileException(ProfileError.DUPLICATE_ID, "id duplicado: '${control.id}'")
        }
        validateHid(control)
        validateCoords(control)
    }

    /** id/label/kind válidos (SPEC-007 §4). */
    private fun validateTextFields(control: TouchControl) {
        if (control.id.isEmpty() || control.id.length > ID_MAX_CHARS) {
            throw ProfileException(ProfileError.FIELD, "id inválido: '${control.id}'")
        }
        if (control.label.isEmpty() || control.label.length > LABEL_MAX_CHARS) {
            throw ProfileException(ProfileError.FIELD, "label inválido en '${control.id}'")
        }
        if (control.kind !in KINDS) {
            throw ProfileException(ProfileError.FIELD, "kind inválido en '${control.id}': ${control.kind}")
        }
    }

    /** R1 reforzada: stick→stick_l/r, dpad→dpad, button→un botón/trigger. */
    private fun validateHid(control: TouchControl) {
        if (control.hid.isEmpty()) return // §8.1: sin asignación es válido (atenuado)
        if (control.hid !in HID_ELEMENTS) {
            throw ProfileException(
                ProfileError.INVALID_HID,
                "hid '${control.hid}' no es un Elemento HID válido (control '${control.id}')",
            )
        }
        val stickPair = setOf("stick_l", "stick_r")
        when (control.kind) {
            KIND_STICK ->
                if (control.hid !in stickPair) {
                    throw ProfileException(
                        ProfileError.INVALID_HID,
                        "stick '${control.id}' debe asignar a stick_l/stick_r",
                    )
                }
            KIND_DPAD ->
                if (control.hid != KIND_DPAD) {
                    throw ProfileException(
                        ProfileError.INVALID_HID,
                        "dpad '${control.id}' debe asignar a dpad",
                    )
                }
            else ->
                if (control.hid in stickPair || control.hid == KIND_DPAD) {
                    throw ProfileException(
                        ProfileError.INVALID_HID,
                        "button '${control.id}' no puede asignar a ${control.hid}",
                    )
                }
        }
    }

    /** Coordenadas normalizadas 0…1 con tamaño mínimo (SPEC-008 §4). */
    private fun validateCoords(control: TouchControl) {
        val originOk = inUnitRange(control.x) && inUnitRange(control.y)
        val sizeOk = control.w >= MIN_SIZE && control.h >= MIN_SIZE
        val insideHorizontal = control.x + control.w <= MAX_COORD + EPS
        val insideVertical = control.y + control.h <= MAX_COORD + EPS
        val inside = insideHorizontal && insideVertical
        if (!(originOk && sizeOk && inside)) {
            throw ProfileException(
                ProfileError.OUT_OF_RANGE,
                "coordenadas fuera de 0…1 en '${control.id}' " +
                    "(x=${control.x},y=${control.y},w=${control.w},h=${control.h})",
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
     * (la config permite reacomodarlas). Rechazo con detalle si es inválida.
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

/** Elementos HID de los cuatro botones de cara (CR-032). */
private val FACE_HIDS: Set<String> =
    setOf("button_a", "button_b", "button_x", "button_y")

/**
 * Vista de distribución de los cuatro botones de cara según la preferencia
 * (CR-032, SPEC-008 §11 R5): Xbox/Play (A abajo, B derecha, X izquierda,
 * Y arriba) o Nintendo (A derecha, B abajo, X arriba, Y izquierda). Los
 * ids, etiquetas y asignaciones viajan con su control; solo se permutan
 * las POSICIONES.
 *
 * CR-070: los cuatro slots (arriba, abajo, izquierda, derecha) se derivan
 * de la geometría del PROPIO perfil (rombo dp de `profile_map.xml` o el
 * perfil guardado), no de constantes porcentuales — así la distribución no
 * pisaba el clúster cuadrado del CR-067/CR-069 y no depende del aspecto de
 * la pantalla. slots: top/bottom por cy extremos y, entre los dos del
 * medio, izquierda/derecha por cx. Si los cuatro botones no forman esa
 * cruz reconocible, el perfil queda tal cual.
 *
 * Es una vista del perfil (el JSON persistido no se reescribe): se aplica
 * sobre el estado vigente y ambos sentidos derivan de los mismos slots,
 * así que Nintendo→apagar restaura el profile intacto y es idempotente.
 */
fun applyFaceDistribution(
    profile: ControlProfile,
    nintendo: Boolean,
): ControlProfile {
    // GEOMETRÍA reconocible: cuatro botones de cara en cruz (top/bottom
    // por cy extremos, izquierda/derecha por cx entre los dos del medio).
    // Sin forma reconocible el perfil queda tal cual (guarda única).
    val faces =
        profile.controls.filter {
            it.kind == ControlProfileCodec.KIND_BUTTON && it.hid in FACE_HIDS
        }
    val byY = faces.sortedBy { it.y }
    val middles = byY.drop(1).dropLast(1)
    val left = middles.minByOrNull { it.x }
    val right = middles.maxByOrNull { it.x }
    val crossResolved =
        faces.size == FACE_HIDS.size &&
            byY.first().y < byY.last().y &&
            middles.size == 2 &&
            left != null &&
            right != null &&
            left.x < right.x
    if (!crossResolved) return profile
    val top = byY.first()
    val bottom = byY.last()
    // hid → slot según distribución; cada control recibe la posición
    // original del control que ocupa ese slot (oportuna antes de copiar).
    val slotOf: Map<String, TouchControl> =
        if (nintendo) {
            mapOf(
                "button_x" to top,
                "button_y" to left,
                "button_a" to right,
                "button_b" to bottom,
            )
        } else {
            mapOf(
                "button_y" to top,
                "button_x" to left,
                "button_b" to right,
                "button_a" to bottom,
            )
        }
    return profile.copy(
        controls =
            profile.controls.map { control ->
                val slot = slotOf[control.hid]
                if (control.kind == ControlProfileCodec.KIND_BUTTON && slot != null) {
                    control.copy(x = slot.x, y = slot.y)
                } else {
                    control
                }
            },
    )
}

/** Coordenadas del default (SPEC-008 §10; layout del gamepad genérico). */
private const val DEF_STICK_W = 0.18f
private const val DEF_STICK_H = 0.24f
private const val DEF_STICK_Y = 0.69f
private const val DEF_BTN_SIZE = 0.09f
private const val DEF_BTN_H = 0.11f
private const val DEF_SHOULDER_W = 0.12f
private const val DEF_SHOULDER_H = 0.10f
private const val DEF_LEFT_STICK_X = 0.06f
private const val DEF_RIGHT_STICK_X = 0.76f
private const val DEF_DPAD_X = 0.05f
private const val DEF_DPAD_Y = 0.37f
private const val DEF_DPAD_W = 0.20f
private const val DEF_DPAD_H = 0.29f
private const val DEF_TRIGGER_SIZE = 0.13f
private const val DEF_TRIGGER_H = 0.10f
private const val DEF_TRIGGER_Y = 0.02f
private const val DEF_LEFT_SIDE_X = 0.04f
private const val DEF_RIGHT_SIDE_X = 0.84f
private const val DEF_SHOULDER_Y = 0.13f
private const val DEF_SELECT_X = 0.39f
private const val DEF_START_X = 0.52f
private const val DEF_FACE_LEFT_X = 0.74f
private const val DEF_FACE_TOP_X = 0.84f
private const val DEF_FACE_RIGHT_X = 0.90f
private const val DEF_FACE_BOTTOM_X = 0.82f
private const val DEF_FACE_TOP_Y = 0.29f
private const val DEF_FACE_SIDE_Y = 0.39f
private const val DEF_FACE_BOTTOM_Y = 0.49f
private const val DEF_STICK_CLICK_Y = 0.76f
private const val DEF_LEFT_STICK_CLICK_X = 0.27f
private const val DEF_RIGHT_STICK_CLICK_X = 0.65f

/** Controles modernos por omisión, organizados como gamepad horizontal. */
private fun defaultControls(): List<TouchControl> =
    listOf(
        triggerDefault("trigger_l", "L2", "trigger_l2", DEF_LEFT_SIDE_X),
        shoulderDefault("button_l1", "L1", "button_l1", DEF_LEFT_SIDE_X),
        faceDefault("button_select", "Select", "button_select", DEF_SELECT_X, DEF_SHOULDER_Y),
        faceDefault("button_start", "Start", "button_start", DEF_START_X, DEF_SHOULDER_Y),
        shoulderDefault("button_r1", "R1", "button_r1", DEF_RIGHT_SIDE_X),
        triggerDefault("trigger_r", "R2", "trigger_r2", DEF_RIGHT_SIDE_X),
        dpadDefault(DEF_DPAD_X, DEF_DPAD_Y),
        stickDefault("stick_left", "L", "stick_l", DEF_LEFT_STICK_X, DEF_STICK_Y),
        faceDefault("button_l3", "L3", "button_l3", DEF_LEFT_STICK_CLICK_X, DEF_STICK_CLICK_Y),
        stickDefault("stick_right", "R", "stick_r", DEF_RIGHT_STICK_X, DEF_STICK_Y),
        faceDefault("button_r3", "R3", "button_r3", DEF_RIGHT_STICK_CLICK_X, DEF_STICK_CLICK_Y),
        faceDefault("btn_y", "Y", "button_y", DEF_FACE_TOP_X, DEF_FACE_TOP_Y),
        faceDefault("btn_x", "X", "button_x", DEF_FACE_LEFT_X, DEF_FACE_SIDE_Y),
        faceDefault("btn_b", "B", "button_b", DEF_FACE_RIGHT_X, DEF_FACE_SIDE_Y),
        faceDefault("btn_a", "A", "button_a", DEF_FACE_BOTTOM_X, DEF_FACE_BOTTOM_Y),
    )

private fun shoulderDefault(
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
        DEF_SHOULDER_Y,
        DEF_SHOULDER_W,
        DEF_SHOULDER_H,
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
        DEF_DPAD_W,
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
