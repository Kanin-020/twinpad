package com.twinpad.android.hid

/**
 * CR-125: ventana máxima de una ronda de conexión BT HID.
 *
 * `BluetoothHidDevice.connect()` puede devolver `true` sin producir ningún
 * callback de estado: el page/L2CAP queda colgado en el stack y la app se
 * quedaba en `REGISTERED` sin reintentar hasta reiniciarla. El canal arma esta
 * ventana al pedir la conexión (o cuando el host la inicia); si vence sin
 * `CONNECTED`, se abortan los enlaces pendientes y se reprograma la ronda.
 *
 * Puro (sin Android) para probarlo en JVM (TEST-0218).
 */
internal class HidConnectWatchdog(private val timeoutMs: Long = TIMEOUT_MS) {
    private var armedAtMs: Long? = null

    /** Arma la ventana; una ronda nueva reinicia la cuenta. */
    fun arm(nowMs: Long) {
        armedAtMs = nowMs
    }

    /** La ronda terminó (conectó, cayó o el canal se detuvo): no vence. */
    fun resolve() {
        armedAtMs = null
    }

    /**
     * `true` una sola vez si la ventana armada lleva [timeoutMs] o más;
     * consume el vencimiento para que un callback tardío no lo repita.
     */
    fun expireIfDue(nowMs: Long): Boolean {
        val armedAt = armedAtMs ?: return false
        if (nowMs - armedAt < timeoutMs) return false
        armedAtMs = null
        return true
    }

    companion object {
        /** Tiempo máximo para que una ronda de conexión HID llegue a CONNECTED. */
        const val TIMEOUT_MS = 10_000L
    }
}
