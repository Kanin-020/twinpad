package com.twinpad.android.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-0212 — SPEC-011 §7.1 (CR-118): el aviso del mapeador se consume una
 * sola vez por identidad de modelo y la marca sobrevive a nuevas instancias
 * (equivalente a reabrir la app).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GamepadProfileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearProfiles() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun auto_open_is_claimed_once_per_model_across_store_instances() {
        val first = GamepadProfileStore(context)

        assertTrue(first.claimAutoOpen("vendor|product|mando"))
        assertFalse(first.claimAutoOpen("vendor|product|mando"))
        assertFalse(GamepadProfileStore(context).claimAutoOpen("vendor|product|mando"))
        assertTrue(first.claimAutoOpen("vendor|product|otro"))
    }

    private companion object {
        const val PREFERENCES = "twinpad_gamepad_profiles"
    }
}
