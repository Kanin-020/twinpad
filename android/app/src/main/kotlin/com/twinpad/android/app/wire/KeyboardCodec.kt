package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Techo de caracteres del form del teclado del juego (CR-044; paridad con `core::game`). */
const val KEYBOARD_MAX_LENGTH: Int = 4096

/** Máximo de bytes UTF-8 del texto base64url (CR-044; paridad con `core::game`). */
const val KEYBOARD_TEXT_MAX_BYTES: Int = 2048

/** Acción pedida por un `keyboard_input` (CR-044). */
enum class KeyboardAction(val wire: String) {
    /** Sustituir el texto del form (equivale a escribir y/o borrar). */
    Update("update"),

    /** Aceptar el texto (botón OK del juego). */
    Confirm("confirm"),

    /** Descartar el teclado sin aceptar. */
    Cancel("cancel"),
}

/** Demanda vigente del teclado del juego (CR-044). Con `on` el texto
 * vigente del form es `text` UTF-8 decodificado y `maxLength` el techo
 * de caracteres que el juego acepta (1–4096). */
data class KeyboardRequestMessage(val on: Boolean, val text: String, val maxLength: Int)

/**
 * Decodificador de `keyboard_request` (SPEC-006 §4, CR-044). Estricto:
 * `type` distinto, flag `on` faltante o no booleano, `on` true sin `text`
 * (el texto vacío sí es válido — el form abre sin texto) o con techo
 * fuera de rango, o `off` con techo/texto → null.
 */
object KeyboardRequestCodec {
    fun decode(line: String): KeyboardRequestMessage? =
        runCatching { Json.parseToJsonElement(line) }
            .getOrNull()
            ?.let { it as? JsonObject }
            ?.takeIf { it["type"]?.jsonPrimitive?.content == "keyboard_request" }
            ?.let { decodeState(it) }

    private fun decodeState(obj: JsonObject) =
        obj["on"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?.let { on -> decodeWithFlags(obj, on) }

    /** `off` no lleva techo de caracteres ni texto vigente (paridad core). */
    private fun decodeWithFlags(
        obj: JsonObject,
        on: Boolean,
    ) = if (!on) {
        val text = obj["text"]?.jsonPrimitive?.content
        val max = obj["max_length"]?.jsonPrimitive?.intOrNull
        if (max == null && text.isNullOrEmpty()) {
            KeyboardRequestMessage(false, "", 0)
        } else {
            null
        }
    } else {
        decodeOn(
            obj["text"]?.jsonPrimitive?.content,
            obj["max_length"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun decodeOn(
        text: String?,
        max: Int?,
    ): KeyboardRequestMessage? =
        text
            ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
            ?.let { decodeValid(max, it) }

    private fun decodeValid(
        max: Int?,
        decoded: ByteArray,
    ): KeyboardRequestMessage? =
        when {
            max == null -> null
            max !in 1..KEYBOARD_MAX_LENGTH -> null
            // El form recién abierto llega con texto vacío (base64url ""): es
            // una demanda on válida — paridad con core.
            decoded.size > KEYBOARD_TEXT_MAX_BYTES -> null
            else -> KeyboardRequestMessage(true, String(decoded, Charsets.UTF_8), max)
        }
}

/**
 * Codificador de `keyboard_input` (SPEC-006 §4, CR-044). Una línea NDJSON
 * del teclado del teléfono hacia Cemu; `text` es el texto completo del
 * form en base64url sin relleno y solo viaja con `update` (0–2048 bytes
 * UTF-8; vacío con `update` borra todo el texto del form).
 * `confirm`/`cancel` van con texto vacío.
 */
object KeyboardInputCodec {
    fun encode(
        action: KeyboardAction,
        text: String,
    ): String {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= KEYBOARD_TEXT_MAX_BYTES) {
            "keyboard_input excede KEYBOARD_TEXT_MAX_BYTES"
        }
        val data =
            if (action == KeyboardAction.Update) {
                // El vacío con `update` es válido: el borrado completo del
                // form llega como un `update` sin texto (paridad con core).
                Base64.getUrlEncoder().withoutPadding().encodeToString(textBytes)
            } else {
                require(text.isEmpty()) { "keyboard_input ${action.wire} no lleva texto" }
                ""
            }
        return "{\"type\":\"keyboard_input\",\"action\":\"${action.wire}\",\"text\":\"$data\"}"
    }
}
