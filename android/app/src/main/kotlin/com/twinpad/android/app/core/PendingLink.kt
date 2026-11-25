package com.twinpad.android.app.core

/**
 * CR-108 (decisión del owner): con una sesión activa, un enlace pedido por QR
 * o por código no se ejecuta en ese momento: se guarda y se ejecuta cuando la
 * sesión termina. La sesión viva nunca se cierra para atenderlo.
 *
 * Mientras la sesión exista (U4 vinculado o U6 reconectando) el payload queda
 * retenido; al entrar en un estado sin sesión se entrega una sola vez.
 */
internal class PendingLink {
    private var payload: String? = null

    /** `true` mientras hay un enlace guardado esperando el final de la sesión. */
    val isWaiting: Boolean
        get() = payload != null

    /** Guarda el payload escaneado/escribo durante la sesión activa. */
    fun save(raw: String) {
        payload = raw
    }

    /** Entrega el payload cuando ya no hay sesión; `null` si aún hay sesión
     * (o si no había nada guardado). */
    fun takeWhenSessionEnded(sessionAlive: Boolean): String? {
        if (sessionAlive) return null
        val pending = payload
        payload = null
        return pending
    }
}
