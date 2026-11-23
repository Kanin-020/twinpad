package com.twinpad.android.app.ui

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-0196 — verifies CR-097: `PairingActivity` se puede recrear cuando el
 * proceso murió en segundo plano. Android restaura sus fragmentos por reflexión
 * (`Fragment.instantiate`), lo que exige constructor sin argumentos; los
 * callbacks no viajan en el estado guardado y los re-cablea la Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingFragmentRestoreTest {
    @Test
    fun `pairing and session fragments can be restored by reflection`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val panel = Fragment.instantiate(context, PairingFragment::class.java.name, null)
        val session = Fragment.instantiate(context, SessionFragment::class.java.name, null)

        assertTrue(panel is PairingFragment)
        assertTrue(session is SessionFragment)
        assertNull((panel as PairingFragment).onPanelReady)
        assertNull((session as SessionFragment).onSessionReady)
    }
}
