package com.twinpad.android.app.ui

import android.content.Context
import android.view.InputDevice
import com.twinpad.android.app.hid.GamepadProfileCodec
import com.twinpad.android.app.hid.GamepadProfileSet
import com.twinpad.android.app.hid.gamepadModelKey

/** Persistencia local de los perfiles de mando (SPEC-011 §5): sobrevive a
 * reinicios y a la desvinculación; no guarda datos de Pairing ni claves. */
internal class GamepadProfileStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(modelKey: String): GamepadProfileSet? {
        val text = preferences.getString(key(modelKey), null) ?: return null
        return GamepadProfileCodec.decode(text)?.takeIf { it.modelKey == modelKey }
    }

    /** SPEC-011 E2: hay datos guardados pero el documento no se puede leer. */
    fun hasCorrupt(modelKey: String): Boolean {
        val text = preferences.getString(key(modelKey), null) ?: return false
        return GamepadProfileCodec.decode(text) == null
    }

    fun save(profiles: GamepadProfileSet) {
        preferences.edit().putString(key(profiles.modelKey), GamepadProfileCodec.encode(profiles)).apply()
    }

    /** SPEC-011 §7.1 (CR-118): el mapeador se abre solo la primera vez que se
     * detecta este modelo; la marca sobrevive a reinicios y reconexiones. */
    fun claimAutoOpen(modelKey: String): Boolean {
        val key = "$AUTO_OPEN_PREFIX$modelKey"
        if (preferences.getBoolean(key, false)) return false
        preferences.edit().putBoolean(key, true).apply()
        return true
    }

    private fun key(modelKey: String): String = "$KEY_PREFIX$modelKey"

    private companion object {
        const val PREFERENCES_NAME = "twinpad_gamepad_profiles"
        const val KEY_PREFIX = "profiles:"
        const val AUTO_OPEN_PREFIX = "auto_open_shown:"
    }
}

/** SPEC-011 §3 (CR-116): identidad del modelo de un mando conectado. */
internal fun gamepadModelKey(device: InputDevice): String =
    gamepadModelKey(device.vendorId, device.productId, device.name, device.descriptor)

/** Nombre legible del mando para el mapeador. */
internal fun gamepadDisplayName(device: InputDevice): String {
    return device.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Mando"
}
