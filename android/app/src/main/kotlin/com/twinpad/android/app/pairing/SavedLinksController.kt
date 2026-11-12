package com.twinpad.android.app.pairing

import com.twinpad.android.app.core.PairingEvent
import com.twinpad.android.app.core.PairingStateMachine
import com.twinpad.android.app.core.PairingUiState
import com.twinpad.android.app.pairing.network.TcpPairingClient
import com.twinpad.android.app.ui.PairingLinkStore
import com.twinpad.android.app.ui.SavedLink

/**
 * CR-094: vinculaciones guardadas (cifradas) para reconectar al abrir la app.
 * Extraído de [PairingViewModel] para mantener su tamaño; el comportamiento no cambia.
 */
internal class SavedLinksController(
    private val machine: PairingStateMachine,
    private val network: TcpPairingClient,
    private val linkStore: PairingLinkStore,
    private val currentState: () -> PairingUiState,
    private val feed: (PairingEvent) -> Unit,
) {
    /** CR-094: al abrir la app, si hay una vinculación guardada, entra en U6 y
     * reanuda con `pair_resume` (el temporizador de reconexión la completa).
     * Reanuda la última guardada (la de `save` más reciente). */
    fun resumeLast() {
        val saved = linkStore.loadAll().lastOrNull() ?: return
        resumeLink(saved)
    }

    /** Vinculaciones guardadas, para la columna de dispositivos. */
    fun all(): List<SavedLink> = linkStore.loadAll()

    /** Conecta a un dispositivo guardado (fila activa): reanuda con `pair_resume`. */
    fun connect(sessionId: String) {
        val saved = linkStore.loadAll().firstOrNull { it.sessionId == sessionId } ?: return
        resumeLink(saved)
    }

    /** Olvida un dispositivo guardado (fila inactiva). Si era el activo, cierra su sesión. */
    fun forget(sessionId: String) {
        linkStore.remove(sessionId)
        val active = (currentState() as? PairingUiState.Linked)?.sessionId
        if (active == sessionId) {
            network.disconnect()
            feed(PairingEvent.Cancelled)
        }
    }

    /** CR-094: guarda la vinculación cifrada para reconectar al abrir la app.
     * La clave sale de la máquina (`sessionKeyOrNull`, vigente tras pair_ack). */
    fun remember(linked: PairingUiState.Linked) {
        val key = machine.sessionKeyOrNull() ?: return
        val host = network.remoteHost() ?: return
        linkStore.save(
            SavedLink(
                sessionId = linked.sessionId,
                sessionKey = key,
                addr = host,
                port = machine.lastConnectPort(),
            ),
        )
    }

    private fun resumeLink(saved: SavedLink) {
        if (machine.resumeFromLink(saved.sessionId, saved.sessionKey, saved.addr, saved.port)) {
            feed(PairingEvent.ResumeTick(0L))
        }
    }
}
