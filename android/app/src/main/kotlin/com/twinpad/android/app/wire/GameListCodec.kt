package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Un juego compatible vivo (SPEC-006 §4), tal como llega en la lista que el
 * server publica por la sesión (§5: juegos_available).
 */
data class GameInfo(
    val gameId: String,
    val gameName: String,
    val capabilities: List<String>,
)

/**
 * Decodificador de `games_available` — SPEC-006 §5/§12 (extensión del canal
 * de sesión). Devuelve `null` para cualquier línea que no sea esta lista
 * (el resto del tráfico de sesión sigue su camino normal).
 */
object GameListCodec {
    private val json = Json

    /** Decodifica la línea; `null` si no es `games_available` válida. */
    fun decode(line: String): List<GameInfo>? =
        try {
            val obj = json.parseToJsonElement(line).jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "games_available") {
                null
            } else {
                obj["games"]?.jsonArray?.map { it.jsonObject.toGame() }
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun kotlinx.serialization.json.JsonObject.toGame(): GameInfo =
        GameInfo(
            gameId = getValue("game_id").jsonPrimitive.content.take(ProtocolConstants.GAME_ID_MAX),
            gameName = getValue("game_name").jsonPrimitive.content.take(ProtocolConstants.GAME_NAME_MAX),
            capabilities = get("capabilities")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        )
}
