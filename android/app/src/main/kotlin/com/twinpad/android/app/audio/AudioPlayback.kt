package com.twinpad.android.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
    private var volume: Float = 1f

    /** CR-115: salida fija (altavoz del teléfono) mientras haya audio BT;
     * `null` deja la salida que elija el sistema. */
    private var outputDevice: AudioDeviceInfo? = null

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is PlaybackCommand.Write -> write(command.chunk)
                    is PlaybackCommand.SetVolume -> {
                        volume = command.volume
                        track?.setVolume(volume)
                    }
                    is PlaybackCommand.SetDevice -> {
                        outputDevice = command.device
                        // La pista vigente no cambia de ruta en caliente: se
                        // descarta y el próximo fragmento la recrea con la
                        // salida nueva (CR-115).
                        releaseTrack()
                    }
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

    /** Cambia el volumen local del track activo (0..1); no altera PCM ni salida del host. */
    fun setVolume(volume: Float) {
        commands.trySend(PlaybackCommand.SetVolume(volume.coerceIn(0f, 1f)))
    }

    /** CR-115: fija la salida del stream (altavoz con audio BT, `null` sistema). */
    fun setOutputDevice(device: AudioDeviceInfo?) {
        commands.trySend(PlaybackCommand.SetDevice(device))
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

    /** Escribe en el track, re-creándolo si cambian los parámetros (§7/§10). */
    private fun write(chunk: AudioChunk) {
        if (track == null || chunk.rate != rate || chunk.channels != channels) {
            releaseTrack()
            if (chunk.rate <= 0 || chunk.channels !in 1..2) return
            track = trackFactory.create(chunk.rate, chunk.channels, outputDevice) ?: return
            track?.setVolume(volume)
            rate = chunk.rate
            channels = chunk.channels
        }
        track?.write(chunk.pcm)
    }

    /** Suelta el track vigente y borra el parámetro cacheado. */
    private fun releaseTrack() {
        track?.stopAndRelease()
        track = null
        rate = 0
        channels = 0
    }
}

internal sealed interface PlaybackCommand {
    /** Escribe un fragmento PCM aceptado por la máquina (§6). */
    data class Write(val chunk: AudioChunk) : PlaybackCommand

    /** Cambia el volumen del track vigente (no del host). */
    data class SetVolume(val volume: Float) : PlaybackCommand

    /** CR-115: cambia la salida; la pista vigente se recrea al escribir. */
    data class SetDevice(val device: AudioDeviceInfo?) : PlaybackCommand

    /** Silencia y libera el track; canal abierto para un nuevo stream. */
    data object Stop : PlaybackCommand

    /** Último comando: libera el track y termina el consumidor. */
    data object Close : PlaybackCommand
}

internal fun interface PlaybackTrackFactory {
    /** Crea un track con ese rate/channels; null si el sistema no puede. */
    fun create(
        rate: Int,
        channels: Int,
        device: AudioDeviceInfo?,
    ): PlaybackTrack?
}

internal interface PlaybackTrack {
    /** PCM s16le bloqueante sobre el track vigente. */
    fun write(pcm: ByteArray)

    /** Volumen local en 0..1. */
    fun setVolume(volume: Float)

    /** Pausa, vacía y libera los recursos del track. */
    fun stopAndRelease()
}

/** Adaptador real sobre AudioTrack del sistema (desconocido en JVM). */
private object AndroidPlaybackTrackFactory : PlaybackTrackFactory {
    override fun create(
        rate: Int,
        channels: Int,
        device: AudioDeviceInfo?,
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
                .apply { if (device != null) setPreferredDevice(device) }
        audioTrack.play()
        return AndroidPlaybackTrack(audioTrack)
    }
}

/** Fallback del track real creado por vía system (no JVM). */
private class AndroidPlaybackTrack(
    private val track: AudioTrack,
) : PlaybackTrack {
    override fun write(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    override fun setVolume(volume: Float) {
        track.setVolume(volume.coerceIn(0f, 1f))
    }

    override fun stopAndRelease() {
        track.pause()
        track.flush()
        track.release()
    }
}
