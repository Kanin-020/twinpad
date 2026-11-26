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
    /** CR-102: envía `pair_revoke` por un socket nuevo (resuelve el host
     * como la conexión normal, SPEC-009), sin esperar el `pair_ack`. */
    private val revokeLink: (SavedLink) -> Unit,
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

    /** Olvida un dispositivo guardado (fila inactiva). Si era el activo,
     * revoca la ligadura en el servidor (CR-102, SPEC-001 §8.3) y cierra su
     * sesión; el `pair_ack` no se espera. */
    fun forget(sessionId: String) {
        val active = (currentState() as? PairingUiState.Linked)?.sessionId
        val saved = linkStore.loadAll().firstOrNull { it.sessionId == sessionId }
        if (active == sessionId) {
            if (saved != null) revokeLink(saved)
            network.disconnect()
            feed(PairingEvent.Cancelled)
        }
        linkStore.remove(sessionId)
    }

    /** CR-094: guarda la vinculación cifrada para reconectar al abrir la app.
     * La clave sale de la máquina (`sessionKeyOrNull`, vigente tras pair_ack). */
    fun remember(linked: PairingUiState.Linked) {
        val key = machine.sessionKeyOrNull() ?: return
        // SPEC-009 §6: se guarda el host del QR (p. ej. `twinpad.local.`), no la
        // IP ya resuelta, para que cada reanudación lo re-resuelva (DHCP).
        val host = machine.lastConnectHost() ?: network.remoteHost() ?: return
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
