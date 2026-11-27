package com.twinpad.android.app.core

/** Enlace pedido con sesión viva, retenido hasta que la sesión termine. */
internal sealed interface PendingLinkRequest {
    /** QR escaneado o código escrito: inicia un Pairing nuevo (CR-108). */
    data class Qr(val payload: String) : PendingLinkRequest

    /** Conexión guardada: reanuda su sesión con `pair_resume` (CR-134). */
    data class SavedDevice(val sessionId: String) : PendingLinkRequest
}

/**
 * CR-108/CR-134 (decisión del owner): con una sesión activa, un enlace pedido
 * por QR/código o desde una conexión guardada no se ejecuta en ese momento: se
 * guarda y se ejecuta cuando la sesión termina. La sesión viva nunca se cierra
 * para atenderlo.
 *
 * Mientras la sesión exista (U4 vinculado o U6 reconectando) el enlace queda
 * retenido; al entrar en un estado sin sesión se entrega una sola vez. Un
 * enlace pedido sustituye al anterior.
 */
internal class PendingLink {
    private var request: PendingLinkRequest? = null

    /** `true` mientras hay un enlace guardado esperando el final de la sesión. */
    val isWaiting: Boolean
        get() = request != null

    /** Guarda el payload escaneado/escrito durante la sesión activa. */
    fun saveQr(raw: String) {
        request = PendingLinkRequest.Qr(raw)
    }

    /** Guarda una conexión guardada para reanudarla al terminar la sesión. */
    fun saveSavedDevice(sessionId: String) {
        request = PendingLinkRequest.SavedDevice(sessionId)
    }

    /** Entrega el enlace cuando ya no hay sesión; `null` si aún hay sesión
     * (o si no había nada guardado). */
    fun takeWhenSessionEnded(sessionAlive: Boolean): PendingLinkRequest? {
        if (sessionAlive) return null
        val pending = request
        request = null
        return pending
    }
}
