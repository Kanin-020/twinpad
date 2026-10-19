package com.twinpad.android.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-053 — verifies SPEC-002/SPEC-006: source-list codec and selection.
 */
class GameListCodecTest {
    @Test
    fun list_and_single_plugin_selection_are_decoded_without_truncation() {
        val plugin =
            """{"game_id":"cemu","game_name":"Cemu","capabilities":["second_screen","audio"]}"""
        val line = """{"type":"games_available","games":[$plugin]}"""
        val games = GameListCodec.decode(line)!!
        assertEquals("cemu", GameListCodec.automaticSelection(games))
        assertEquals("Cemu", games.single().gameName)
        assertEquals(
            "{\"type\":\"game_select\",\"game_id\":\"cemu\"}",
            GameListCodec.selectionLine("cemu"),
        )
        assertEquals(
            "cemu",
            GameListCodec.selectedGameId("""{"type":"game_selected","game_id":"cemu"}"""),
        )
    }

    /**
     * TEST-055 — verifies SPEC-002/SPEC-006: stale selection clears on liveness change.
     */
    @Test
    fun removed_selection_is_cleared_before_choosing_from_the_updated_list() {
        val currentSelection = "cemu"
        val updatedGames =
            listOf(
                GameInfo("other_a", "Other A", emptyList()),
                GameInfo("other_b", "Other B", emptyList()),
            )
        assertNull(GameListCodec.retainedSelection(currentSelection, updatedGames))
        assertNull(GameListCodec.automaticSelection(updatedGames))
        assertTrue(GameListCodec.needsExplicitSelection(updatedGames, null))
        assertTrue(GameListCodec.needsExplicitSelection(updatedGames, "cemu"))
        assertEquals("other_a", GameListCodec.retainedSelection("other_a", updatedGames))
        assertTrue(!GameListCodec.needsExplicitSelection(updatedGames, "other_a"))
    }

    @Test
    fun several_plugins_have_no_automatic_selection_and_bad_contracts_fail_closed() {
        val plugins =
            listOf("a", "b").joinToString(",") { id ->
                """{"game_id":"$id","game_name":"${id.uppercase()}","capabilities":[]}"""
            }
        val line = """{"type":"games_available","games":[$plugins]}"""
        val games = GameListCodec.decode(line)!!
        assertNull(GameListCodec.automaticSelection(games))
        assertNull(GameListCodec.decode("""{"type":"games_available","games":[],"extra":true}"""))
        val invalidPlugin =
            """{"game_id":"Bad ID","game_name":"Bad","capabilities":[]}"""
        assertNull(GameListCodec.decode("""{"type":"games_available","games":[$invalidPlugin]}"""))
        assertNull(
            GameListCodec.selectedGameId("""{"type":"game_selected","game_id":"bad id"}"""),
        )
        assertTrue(games.map { it.gameId }.toSet().size == games.size)
    }
}
