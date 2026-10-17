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
 * `AudioTrack` en modo STREAM. Los comandos se serializan en un hilo IO;
 * cambiar parámetros o recibir `audio_stop` libera la pista, no el canal de
 * comandos, para que un nuevo stream pueda reproducirse durante la sesión.
 */
class AudioPlayback internal constructor(
    private val trackFactory: PlaybackTrackFactory = AndroidPlaybackTrackFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<PlaybackCommand>(capacity = Channel.UNLIMITED)

    private var track: PlaybackTrack? = null
    private var rate: Int = 0
    private var channels: Int = 0

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is PlaybackCommand.Write -> write(command.chunk)
                    PlaybackCommand.Stop -> releaseTrack()
                    PlaybackCommand.Close -> {
                        releaseTrack()
                        break
                    }
                }
            }
        }
    }

    /** Encola los fragmentos aceptados por la máquina hacia la salida. */
    fun drain(chunks: List<AudioChunk>) {
        for (chunk in chunks) {
            commands.trySend(PlaybackCommand.Write(chunk))
        }
    }

    /** Silencia el stream vigente sin cerrar el reproductor para futuros streams. */
    fun stop() {
        commands.trySend(PlaybackCommand.Stop)
    }

    /** Libera la salida y finaliza el consumidor al terminar la sesión. */
    fun release() {
        commands.trySend(PlaybackCommand.Close)
        commands.close()
    }

    private fun write(chunk: AudioChunk) {
        if (track == null || chunk.rate != rate || chunk.channels != channels) {
            releaseTrack()
            if (chunk.rate <= 0 || chunk.channels !in 1..2) return
            track = trackFactory.create(chunk.rate, chunk.channels) ?: return
            rate = chunk.rate
            channels = chunk.channels
        }
        track?.write(chunk.pcm)
    }

    private fun releaseTrack() {
        track?.stopAndRelease()
        track = null
        rate = 0
        channels = 0
    }
}

internal sealed interface PlaybackCommand {
    data class Write(val chunk: AudioChunk) : PlaybackCommand

    data object Stop : PlaybackCommand

    data object Close : PlaybackCommand
}

internal fun interface PlaybackTrackFactory {
    fun create(
        rate: Int,
        channels: Int,
    ): PlaybackTrack?
}

internal interface PlaybackTrack {
    fun write(pcm: ByteArray)

    fun stopAndRelease()
}

private object AndroidPlaybackTrackFactory : PlaybackTrackFactory {
    override fun create(
        rate: Int,
        channels: Int,
    ): PlaybackTrack? {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format =
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .build()
        val minBuffer = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val audioTrack =
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
        audioTrack.play()
        return AndroidPlaybackTrack(audioTrack)
    }
}

private class AndroidPlaybackTrack(
    private val track: AudioTrack,
) : PlaybackTrack {
    override fun write(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    override fun stopAndRelease() {
        track.pause()
        track.flush()
        track.release()
    }
}
