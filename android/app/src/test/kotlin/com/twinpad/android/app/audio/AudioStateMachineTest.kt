package com.twinpad.android.app.audio

import com.twinpad.android.app.wire.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * TEST-020 — verifies SPEC-005 (§6: estados A0/A1/A2; §9: chunk corrupto o
 * repetido se descarta y la reproducción continúa; §10: chunks con
 * parámetros inconsistentes descartados; jitter 500 ms fijo).
 */
class AudioStateMachineTest {
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun startLine(
        rate: Int = ProtocolConstants.AUDIO_DEMO_RATE,
        channels: Int = 1,
    ): String = """{"type":"audio_start","rate":$rate,"channels":$channels,"sample_fmt":"s16le"}"""

    private fun chunkLine(
        seq: Long,
        samples: Int = 8,
    ): String = """{"type":"audio_chunk","seq":$seq,"ts":0,"data":"${b64(ByteArray(samples * 2))}"}"""

    @Test
    fun starts_inactive_and_starts_playing_on_audio_start() {
        val audio = AudioStateMachine()
        assertEquals(AudioState.INACTIVE, audio.state)
        assertTrue(audio.onLine(startLine(), nowMs = 0))
        assertEquals(AudioState.PLAYING, audio.state)
        assertEquals(ProtocolConstants.AUDIO_DEMO_RATE, audio.rate)
    }

    @Test
    fun chunk_before_audio_start_is_ignored() {
        val audio = AudioStateMachine()
        assertFalse(audio.onLine(chunkLine(0), nowMs = 0))
        assertEquals(AudioState.INACTIVE, audio.state)
        assertTrue(audio.drainPending().isEmpty())
    }

    @Test
    fun chunks_are_queued_in_order_and_drained() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        audio.onLine(chunkLine(0), nowMs = 10)
        audio.onLine(chunkLine(1), nowMs = 20)
        val pending = audio.drainPending()
        assertEquals(listOf(0L, 1L), pending.map { it.seq })
        assertTrue(audio.drainPending().isEmpty())
    }

    @Test
    fun stale_or_repeated_seq_is_discarded() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        audio.onLine(chunkLine(5), nowMs = 10)
        assertFalse(audio.onLine(chunkLine(5), nowMs = 20)) // repetido
        assertFalse(audio.onLine(chunkLine(2), nowMs = 30)) // viejo
        assertEquals(1, audio.drainPending().size)
    }

    @Test
    fun corrupted_chunk_is_discarded_and_playback_continues() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        audio.onLine(chunkLine(0), nowMs = 10)
        // data no-base64 (§9): descartado, sin romper el estado.
        assertFalse(
            audio.onLine(
                """{"type":"audio_chunk","seq":1,"ts":0,"data":"¡no es base64!"}""",
                nowMs = 20,
            ),
        )
        // PCM de longitud impar (no entero de muestras s16le, §4): descartado.
        assertFalse(
            audio.onLine(
                """{"type":"audio_chunk","seq":2,"ts":0,"data":"${b64(ByteArray(3))}"}""",
                nowMs = 30,
            ),
        )
        // El siguiente chunk válido se reproduce normalmente (§13).
        assertTrue(audio.onLine(chunkLine(3), nowMs = 40))
        assertEquals(listOf(0L, 3L), audio.drainPending().map { it.seq })
    }

    @Test
    fun chunk_over_16kib_is_discarded() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        val tooBig = ByteArray(ProtocolConstants.AUDIO_CHUNK_MAX_BYTES + 2)
        val line =
            """{"type":"audio_chunk","seq":0,"ts":0,"data":"${b64(tooBig)}"}"""
        assertFalse(audio.onLine(line, nowMs = 10))
    }

    @Test
    fun silence_over_jitter_buffer_goes_to_interrupted() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        audio.onLine(chunkLine(0), nowMs = 100)
        assertEquals(AudioState.PLAYING, audio.tick(nowMs = 100 + AudioStateMachine.JITTER_BUFFER_MS))
        assertEquals(
            AudioState.INTERRUPTED,
            audio.tick(nowMs = 100 + AudioStateMachine.JITTER_BUFFER_MS + 1),
        )
        // A2→A1: el siguiente chunk reanuda sin re-pairing (§8.2).
        assertTrue(audio.onLine(chunkLine(1), nowMs = 1_000))
        assertEquals(AudioState.PLAYING, audio.state)
    }

    @Test
    fun audio_stop_returns_to_inactive() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        audio.onLine(chunkLine(0), nowMs = 10)
        assertTrue(
            audio.onLine(
                """{"type":"audio_stop","reason":"plugin_stopped"}""",
                nowMs = 20,
            ),
        )
        assertEquals(AudioState.INACTIVE, audio.state)
        // §14: nada se reproduce tras el stop.
        assertFalse(audio.onLine(chunkLine(1), nowMs = 30))
    }

    @Test
    fun re_announced_start_resets_the_stream() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        audio.onLine(chunkLine(10), nowMs = 10)
        // §10: el server re-anuncia (dispositivo cambiado) → nueva vida.
        assertTrue(audio.onLine(startLine(rate = 48_000, channels = 2), nowMs = 20))
        assertEquals(48_000, audio.rate)
        assertEquals(2, audio.channels)
        // El seq de la vida anterior no bloquea la nueva (reinicio de lastSeq).
        assertTrue(audio.onLine(chunkLine(0), nowMs = 30))
    }

    @Test
    fun invalid_line_is_ignored_without_state_change() {
        val audio = AudioStateMachine()
        audio.onLine(startLine(), nowMs = 0)
        assertFalse(audio.onLine("{\"type\":\"audio_rewind\"}", nowMs = 10))
        assertEquals(AudioState.PLAYING, audio.state)
    }
}
