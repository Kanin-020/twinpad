package com.twinpad.android.app.pairing

/** Tracks pending and automatic source selection during one pairing session. */
internal class PairingSelectionState {
    var pending = false
    var requestedGameId: String? = null
    var automaticGameId: String? = null

    fun clear() {
        pending = false
        requestedGameId = null
        automaticGameId = null
    }
}
