package com.twinpad.android.app.surface

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistencia de perfiles (SPEC-008 §5: guardar por juego, carga
 * automática al detectar el juego — AC-2). Un archivo JSON por juego en
 * `filesDir/profiles/`; la corrupción se descarta y cae al default con
 * aviso (§9). Toda E/S fuera del hilo principal (ENG §5).
 */
class ControlProfileStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "profiles").apply { mkdirs() }

    /** Nombre de archivo seguro para un juego (id legible → slug). */
    private fun fileFor(game: String): File {
        val slug = game.lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifEmpty { "default" }
        return File(dir, "$slug.json")
    }

    /** Guarda el perfil validado; `false` si la validación rechaza (§9). */
    suspend fun save(profile: ControlProfile): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ControlProfileCodec.validate(profile)
                fileFor(profile.game).writeText(ControlProfileCodec.encode(profile))
                true
            } catch (e: ProfileException) {
                false
            }
        }

    /** Carga el perfil del juego; `null` si no existe o está corrupto. */
    suspend fun load(game: String): ControlProfile? =
        withContext(Dispatchers.IO) {
            val file = fileFor(game)
            if (!file.exists()) {
                null
            } else {
                runCatching { ControlProfileCodec.decode(file.readText()) }.getOrNull()
            }
        }

    /** Elimina el perfil del juego. */
    suspend fun delete(game: String): Boolean = withContext(Dispatchers.IO) { fileFor(game).delete() }
}
