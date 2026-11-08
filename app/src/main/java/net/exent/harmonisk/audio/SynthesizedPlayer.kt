package net.exent.harmonisk.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

abstract class SynthesizedPlayer(
    private val durationSeconds: Float,
    private val volume: Float = 0.75f
) {
    private var track: AudioTrack? = null
    private var released = false

    fun play() {
        release()
        released = false
        Thread {
            val samples = ShortArray((AudioConstants.SAMPLE_RATE * durationSeconds).toInt()) { index ->
                (sample(index / AudioConstants.SAMPLE_RATE.toFloat()).coerceIn(-1f, 1f) * Short.MAX_VALUE)
                    .toInt()
                    .toShort()
            }
            val audio = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioConstants.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audio.write(samples, 0, samples.size)
            synchronized(this) {
                if (released) {
                    audio.release()
                    return@Thread
                }
                audio.setVolume(volume)
                audio.play()
                track = audio
            }
        }.apply {
            name = "synthesized-player"
            isDaemon = true
            start()
        }
    }

    fun release() {
        synchronized(this) {
            released = true
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
            track = null
        }
    }

    protected abstract fun sample(time: Float): Float
}
