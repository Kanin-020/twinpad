package com.twinpad.android.app.pairing.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** CR-057 (SPEC-009 §4/§6): re-resolución antes de CADA intento de conexión
 * (U2→U3, reanudación CR-056 y reconexión media SPEC-003 §8.2) — la IP
 * renovada por DHCP ya no rompe el QR (REQ-002 AC-4). Sin respuesta mDNS
 * dentro del timeout, [ServerDiscovery.resolveServer] devuelve `null` y el
 * flujo resultante es el E1 de SPEC-002 §9 intacto («Servidor no
 * disponible»). Las IP literales del QR se usan directo (comportamiento
 * previo), sin tocar NsdManager (§4). */
object HostResolution {
    /** ¿Es `s` IP literal (v4)? Literal → directa; nombre → mDNS (§4). */
    fun isIpLiteral(addr: String): Boolean = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(addr)

    /** Host del anunciador fijo (SPEC-009 §4): único nombre válido en v1. */
    const val SPEC009_HOST: String = "twinpad.local."
}

/**
 * Descubrimiento mDNS/DNS-SD del Servidor PC — CR-057 (SPEC-009). La app
 * resuelve `s` vía [NsdManager] cuando no es IP literal: servicio
 * `_twinpad._tcp` (Android añade el dominio `local.`), instancia `Twinpad`;
 * el puerto lo entrega el anuncio (igual al server_port del §3, TXT
 * `ver=twinpad.v1`).
 */
class ServerDiscovery(private val context: Context) {
    /** Resuelve el par (host, puerto) para el `s` del QR (§4/§6).
     * IP literal: `(host, null)` — el caller conserva su puerto.
     * Nombre: `(hostAddress, deliveryPort)` vía NsdManager.
     * `null` en el puerto sin respuesta mDNS → E1 del caller. */
    suspend fun resolveServer(
        host: String,
        qrPort: Int,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): Pair<String, Int>? =
        when {
            HostResolution.isIpLiteral(host) -> host to qrPort
            host != HostResolution.SPEC009_HOST -> null
            else ->
                (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)
                    ?.let { resolveViaNsd(it, timeoutMs) }
        }

    /** Descubre y resuelve la instancia `Twinpad._twinpad._tcp.local.`:
     * callbacks NSD en un solo tramo suspend con timeout del resolver. */
    private suspend fun resolveViaNsd(
        nsd: NsdManager,
        timeoutMs: Long,
    ): Pair<String, Int>? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener =
                    object : NsdManager.DiscoveryListener {
                        private val resolved = mutableListOf<NsdServiceInfo>()

                        override fun onDiscoveryStarted(serviceType: String) = Unit

                        override fun onServiceFound(info: NsdServiceInfo) {
                            // §4: instancia fija «Twinpad»; extras ignorados.
                            if (info.serviceName != "Twinpad") return
                            nsd.resolveService(
                                info,
                                object : NsdManager.ResolveListener {
                                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                                        val address =
                                            resolved.host?.hostAddress
                                                ?: return waitNext()
                                        stop()
                                        if (cont.isActive) {
                                            cont.resume(address to resolved.port)
                                        }
                                    }

                                    override fun onResolveFailed(
                                        info: NsdServiceInfo,
                                        errorCode: Int,
                                    ) = waitNext()
                                },
                            )
                        }

                        override fun onServiceLost(info: NsdServiceInfo) = Unit

                        override fun onDiscoveryStopped(serviceType: String) = Unit

                        override fun onStartDiscoveryFailed(
                            serviceType: String,
                            errorCode: Int,
                        ) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onStopDiscoveryFailed(
                            serviceType: String,
                            errorCode: Int,
                        ) = Unit

                        /** Un intento fallido no zanja: puede llegar otro evento. */
                        private fun waitNext() = Unit

                        private fun stop() {
                            runCatching { nsd.stopServiceDiscovery(this) }
                        }
                    }
                runCatching { nsd.discoverServices("_twinpad._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure {
                        if (cont.isActive) cont.resume(null)
                    }
                cont.invokeOnCancellation {
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
            }
        }

    private companion object {
        /** Timeout del resolver aplicado (SPEC-009 §6: sin respuesta → E1). */
        const val DISCOVERY_TIMEOUT_MS: Long = 5_000L
    }
}
