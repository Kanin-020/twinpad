package com.twinpad.android.app.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Un juego compatible vivo (SPEC-006 §4), tal como llega en la lista que el
 * server publica por la sesión (§5: games_available).
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

    /** Única fuente se selecciona automáticamente; varias requieren acción explícita. */
    fun automaticSelection(games: List<GameInfo>): String? = games.singleOrNull()?.gameId

    /** Conserva la selección actual solo mientras siga presente en la lista viva. */
    fun retainedSelection(
        currentGameId: String?,
        games: List<GameInfo>,
    ): String? = currentGameId?.takeIf { selected -> games.any { it.gameId == selected } }

    fun needsExplicitSelection(
        games: List<GameInfo>,
        pendingGameId: String?,
    ): Boolean {
        return games.size > 1 && retainedSelection(pendingGameId, games) == null
    }

    fun selectionLine(gameId: String): String = "{\"type\":\"game_select\",\"game_id\":\"$gameId\"}"

    fun selectedGameId(line: String): String? =
        try {
            val obj = json.parseToJsonElement(line).jsonObject
            if (
                obj["type"]?.jsonPrimitive?.content != "game_selected" ||
                obj.keys != setOf("type", "game_id")
            ) {
                null
            } else {
                obj["game_id"]?.jsonPrimitive?.content?.takeIf(::validGameId)
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun validGameId(value: String): Boolean =
        value.isNotEmpty() && value.length <= ProtocolConstants.GAME_ID_MAX &&
            value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }

    /** Decodifica la línea; `null` si no es `games_available` válida. */
    fun decode(line: String): List<GameInfo>? =
        try {
            val obj = json.parseToJsonElement(line).jsonObject
            if (
                obj["type"]?.jsonPrimitive?.content != "games_available" ||
                obj.keys != setOf("type", "games")
            ) {
                null
            } else {
                obj["games"]?.jsonArray?.map { it.jsonObject.toGame() }
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun kotlinx.serialization.json.JsonObject.toGame(): GameInfo =
        GameInfo(
            gameId = getValue("game_id").jsonPrimitive.content.also { require(validGameId(it)) },
            gameName =
                getValue("game_name").jsonPrimitive.content.also {
                    require(it.isNotEmpty() && it.length <= ProtocolConstants.GAME_NAME_MAX)
                },
            capabilities =
                get("capabilities")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        )
}
