package com.twinpad.android.app.pairing.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-0134 — CR-057 (SPEC-009 §4): clasificación del `s` del QR — IP
 * literal → conexión directa; `twinpad.local.` → resolución vía NsdManager.
 * La resolución NSD real no se mockea (test-strategy §10): se verifica el
 * clasificador puro; el anuncio server con el daemon real queda cubierto
 * por TEST-0130 en `server/src/mdns.rs`.
 */
class HostResolutionTest {
    @Test
    fun `ipv4 literals are not resolved via nsd`() {
        assertTrue(HostResolution.isIpLiteral("127.0.0.1"))
        assertTrue(HostResolution.isIpLiteral("192.168.100.101"))
        assertTrue(HostResolution.isIpLiteral("10.0.0.7"))
    }

    @Test
    fun `twinpad local name routes to mdns resolution`() {
        assertFalse(HostResolution.isIpLiteral(HostResolution.SPEC009_HOST))
        assertFalse(HostResolution.isIpLiteral("twinpad"))
        assertFalse(HostResolution.isIpLiteral("twinpad.local"))
    }
}
