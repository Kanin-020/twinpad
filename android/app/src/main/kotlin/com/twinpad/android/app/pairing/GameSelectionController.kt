package com.twinpad.android.app.pairing

import com.twinpad.android.app.wire.GameInfo
import com.twinpad.android.app.wire.GameListCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SPEC-006 §5: lista de juegos compatibles publicada por el server y la
 * selección del plugin para la sesión. Extraído de [PairingViewModel] para
 * mantener su tamaño; el comportamiento no cambia.
 */
internal class GameSelectionController(
    private val network: com.twinpad.android.app.pairing.network.TcpPairingClient,
    private val clearMediaForSelectionChange: () -> Unit,
) {
    private val selection = PairingSelectionState()

    /** Juegos compatibles vivos (SPEC-006 §5): lista publicada por el server. */
    private val _availableGames = MutableStateFlow<List<GameInfo>>(emptyList())
    val availableGames: StateFlow<List<GameInfo>> = _availableGames

    /** Plugin confirmado para esta sesión (SPEC-006). */
    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId

    private val _selectionRequest = MutableSharedFlow<List<GameInfo>>(extraBufferCapacity = 1)
    val selectionRequest: SharedFlow<List<GameInfo>> = _selectionRequest

    /** Solicita la fuente seleccionada; medios previos se limpian al confirmarse. */
    fun select(gameId: String) {
        if (_availableGames.value.none { it.gameId == gameId }) return
        sendSelection(gameId)
    }

    /** Sesión terminada: sin juegos ni selección vigentes. */
    fun clear() {
        _availableGames.value = emptyList()
        _selectedGameId.value = null
        selection.clear()
    }

    fun onGamesAvailable(games: List<GameInfo>) {
        _availableGames.value = games
        val pendingId = selection.requestedGameId
        if (pendingId != null && GameListCodec.retainedSelection(pendingId, games) == null) {
            selection.pending = false
            selection.requestedGameId = null
        }
        val selected = _selectedGameId.value
        val retained = GameListCodec.retainedSelection(selected, games)
        if (selected != null && retained == null) {
            _selectedGameId.value = null
            clearMediaForSelectionChange()
        }

        when {
            games.isEmpty() -> selection.automaticGameId = null
            games.size == 1 -> {
                selection.automaticGameId = games.single().gameId
                if (selected != selection.automaticGameId && selection.requestedGameId != selection.automaticGameId) {
                    sendSelection(selection.automaticGameId!!)
                }
            }
            else -> {
                selection.automaticGameId = null
                if (GameListCodec.needsExplicitSelection(games, selection.requestedGameId)) {
                    _selectionRequest.tryEmit(games)
                }
            }
        }
    }

    fun onGameSelected(gameId: String) {
        if (!selection.pending || selection.requestedGameId != gameId) return
        val previous = _selectedGameId.value
        if (previous != gameId) clearMediaForSelectionChange()
        _selectedGameId.value = gameId
        selection.pending = false
        selection.requestedGameId = null
    }

    private fun sendSelection(gameId: String) {
        selection.requestedGameId = gameId
        selection.pending = true
        network.send(GameListCodec.selectionLine(gameId))
    }
}
