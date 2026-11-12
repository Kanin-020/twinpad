package com.twinpad.android.app.pairing

import com.twinpad.android.app.audio.AudioPlayback
import com.twinpad.android.app.audio.AudioState
import com.twinpad.android.app.audio.AudioStateMachine
import com.twinpad.android.app.stream.StreamStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SPEC-005 §5: estado del audio del stream y su reproducción. Extraído de
 * [PairingViewModel] para mantener su tamaño; el comportamiento no cambia.
 */
internal class AudioSessionController(
    private val audioMachine: AudioStateMachine,
    private val playback: AudioPlayback,
    private val streamStats: StreamStats,
) {
    /** Estado del stream de audio (SPEC-005 §5) para la vista. */
    private val _audioState = MutableStateFlow(AudioState.INACTIVE)
    val audioState: StateFlow<AudioState> = _audioState

    /** Avance periódico del estado de audio (jitter buffer, A2 en SPEC-005 §6). */
    fun tick(now: Long) {
        val audioStatus = audioMachine.tick(now)
        if (audioStatus != _audioState.value) {
            _audioState.value = audioStatus
        }
    }

    /** Una línea `audio_*` del server. */
    fun handleLine(
        line: String,
        lineType: String?,
    ) {
        if (audioMachine.onLine(line, System.currentTimeMillis())) {
            _audioState.value = audioMachine.state
            when (lineType) {
                "audio_start", "audio_stop" -> playback.stop()
                "audio_chunk" -> playback.drain(audioMachine.drainPending())
            }
        }
        if (lineType == "audio_chunk") {
            streamStats.onAudioChunkReceived(line.length)
        } else {
            streamStats.onAudio(line.length)
        }
    }

    /** Fin de sesión o cambio de fuente: la reproducción vuelve a inactiva. */
    fun reset() {
        audioMachine.reset()
        playback.stop()
        _audioState.value = AudioState.INACTIVE
    }

    fun setVolume(volume: Float) = playback.setVolume(volume)
}
