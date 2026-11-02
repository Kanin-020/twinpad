package com.twinpad.android.app.stream

import android.os.Trace

/** Instrumentación para Perfetto: etiquetas fijas para no filtrar
 * contenido de frames ni crear tracks de cardinalidad alta. */
internal object VideoTrace {
    /** Mide la sección nombrada alrededor del bloque (A/B de systrace). */
    fun <T> section(
        name: String,
        block: () -> T,
    ): T {
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    /** Publica un contador por nombre fijo (p. ej. tiles por frame). */
    fun counter(
        name: String,
        value: Long,
    ) {
        Trace.setCounter(name, value)
    }
}
