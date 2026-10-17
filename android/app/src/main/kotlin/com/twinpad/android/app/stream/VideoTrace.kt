package com.twinpad.android.app.stream

import android.os.Trace

/** Instrumentation for Perfetto; fixed labels avoid leaking frame content or creating high-cardinality tracks. */
internal object VideoTrace {
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

    fun counter(
        name: String,
        value: Long,
    ) {
        Trace.setCounter(name, value)
    }
}
