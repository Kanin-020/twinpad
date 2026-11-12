package com.twinpad.android.app.ui

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Vinculación guardada tras un emparejamiento (CR-094). */
internal data class SavedLink(
    val sessionId: String,
    val sessionKey: ByteArray,
    val addr: String,
    val port: Int,
    val name: String = "",
)

/**
 * CR-094: vinculaciones persistentes cifradas con Android Keystore. Guarda una
 * entrada por `sessionId` para reconectar con `pair_resume` sin re-escanear el QR
 * y para mostrar la lista de dispositivos guardados.
 */
internal open class PairingLinkStore(context: Context) {
    private val preferences by lazy {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Todas las vinculaciones guardadas, sin orden garantizado. */
    open fun loadAll(): List<SavedLink> {
        val raw = preferences.getString(KEY_LINKS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SavedLink(
                sessionId = item.getString("session_id"),
                sessionKey = Base64.decode(item.getString("session_key"), Base64.NO_WRAP),
                addr = item.getString("server_addr"),
                port = item.getInt("server_port"),
                name = item.optString("name", ""),
            )
        }
    }

    /** Guarda o actualiza la vinculación del mismo `sessionId`. */
    open fun save(link: SavedLink) {
        val kept = loadAll().filter { it.sessionId != link.sessionId } + link
        writeAll(kept)
    }

    /** Olvida una vinculación concreta (botón Olvidar). */
    open fun remove(sessionId: String) {
        writeAll(loadAll().filter { it.sessionId != sessionId })
    }

    private fun writeAll(links: List<SavedLink>) {
        val array = JSONArray()
        links.forEach { link ->
            array.put(
                JSONObject()
                    .put("session_id", link.sessionId)
                    .put("session_key", Base64.encodeToString(link.sessionKey, Base64.NO_WRAP))
                    .put("server_addr", link.addr)
                    .put("server_port", link.port)
                    .put("name", link.name),
            )
        }
        preferences.edit().putString(KEY_LINKS, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "twinpad_pairing_link"
        const val KEY_LINKS = "links"
    }
}
