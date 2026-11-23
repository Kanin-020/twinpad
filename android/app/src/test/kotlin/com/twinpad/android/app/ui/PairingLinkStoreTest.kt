package com.twinpad.android.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-0198 — regresión: la app se caía al abrirla cuando el fichero cifrado de
 * vinculaciones era ilegible (`AEADBadTagException`/`KeyStoreException` al leer
 * el keyset: clave maestra del Keystore invalidada o `shared_prefs` restaurado
 * por copia de seguridad sin su clave). `PairingLinkStore` se lee en el `init`
 * del ViewModel (CR-094), así que el fallo impedía arrancar. Ahora el almacén se
 * descarta y queda vacío. En la JVM de test el Keystore no existe, así que
 * `create()` falla siempre: es exactamente el camino degradado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingLinkStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `unreadable encrypted store degrades to empty instead of crashing`() {
        val store = PairingLinkStore(app)

        assertEquals(emptyList<SavedLink>(), store.loadAll())

        store.save(SavedLink("id", ByteArray(32), "twinpad.local.", 8420))
        store.remove("id")

        assertEquals(emptyList<SavedLink>(), store.loadAll())
    }
}
