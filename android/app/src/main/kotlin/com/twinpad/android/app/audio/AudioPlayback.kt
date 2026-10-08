package com.twinpad.android.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Adaptador de reproducción del stream de audio (SPEC-005 §7 paso 3) sobre
 * `AudioTrack` en modo STREAM: configura la salida con los parámetros de
 * `audio_start`, encola cada fragmento aceptado y libera recursos al
 * terminar. Es la única clase con efecto de audio (ENG §5 P11); la lógica
 * vive en [AudioStateMachine].
 *
 * Los fragmentos se encolan en [pendingChunks] y un hilo propio los escribe
 * con `WRITE_BLOCKING`: fuera del hilo de UI (ENG §9) y sin descartar audio
 * cuando el buffer interno se llena (el audio demo emite continuo).
 *
 * Silencio (§5): sin `audio_start` vigente no se reproduce nada.
 */
class AudioPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingChunks = Channel<AudioChunk>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (chunk in pendingChunks) {
                reconfigureIfNeeded(chunk.rate, chunk.channels)
                val t = track ?: return@launch
                t.write(chunk.pcm, 0, chunk.pcm.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    private var track: AudioTrack? = null
    private var rate: Int = 0
    private var channels: Int = 0

    /** Encola los fragmentos aceptados por la máquina hacia la salida. */
    fun drain(chunks: List<AudioChunk>) {
        for (chunk in chunks) {
            pendingChunks.trySend(chunk)
        }
    }

    /** Silencia y libera la salida (A0; §7 paso 4 / fin de sesión). */
    fun release() {
        pendingChunks.close()
        track?.let {
            it.pause()
            it.flush()
            it.release()
        }
        track = null
        rate = 0
        channels = 0
    }

    /** Re-configura la salida si cambiaron los parámetros (§10 re-anuncio). */
    private fun reconfigureIfNeeded(
        newRate: Int,
        newChannels: Int,
    ) {
        if (track != null && newRate == rate && newChannels == channels) {
            return
        }
        release()
        if (newRate <= 0 || newChannels <= 0) return
        val channelMask =
            if (newChannels >= 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
        val format =
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(newRate)
                .setChannelMask(channelMask)
                .build()
        val minBuffer = AudioTrack.getMinBufferSize(newRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        rate = newRate
        channels = newChannels
        track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        track?.play()
    }
}
