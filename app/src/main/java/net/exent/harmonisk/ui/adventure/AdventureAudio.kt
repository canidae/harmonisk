package net.exent.harmonisk.ui.adventure

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import net.exent.harmonisk.audio.AudioConstants
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

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
            val samples = ShortArray((SAMPLE_RATE * durationSeconds).toInt()) { index ->
                (sample(index / SAMPLE_RATE.toFloat()).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
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
                        .setSampleRate(SAMPLE_RATE)
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

    private companion object {
        const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
    }
}

class AdventureFanfarePlayer : SynthesizedPlayer(3.3f) {
    private val random = Random(11)

    override fun sample(time: Float): Float {
        var sample = 0f
        val drumEnd = 1.35f
        val fanfareStart = 1.35f
        val melody = listOf(
            523.25f to 0f,
            659.25f to 0.19f,
            783.99f to 0.38f,
            1046.50f to 0.57f
        )
        if (time < drumEnd) {
            val noise = random.nextFloat() * 2f - 1f
            val pulse = 0.45f + 0.55f * ((sin(2.0 * PI * 19.0 * time) + 1.0) / 2.0).toFloat()
            val envelope = 1f - time / drumEnd * 0.75f
            sample += noise * pulse * envelope * 0.42f
            sample += (sin(2.0 * PI * 92.0 * time).toFloat()) * envelope * 0.16f
        }
        if (time >= fanfareStart) {
            val local = time - fanfareStart
            melody.forEach { (frequency, start) ->
                if (local >= start && local < start + 0.23f) {
                    sample += brass(local - start, frequency) * 0.34f
                }
            }
            if (local >= 0.9f) {
                val chord = listOf(523.25f, 659.25f, 783.99f, 1046.50f)
                chord.forEach { frequency ->
                    sample += brass(local - 0.9f, frequency) * 0.16f
                }
            }
        }
        return sample
    }

    private fun brass(time: Float, frequency: Float): Float {
        val envelope = min(1f, time / 0.018f) * exp(-2.2f * time)
        val phase = 2.0 * PI * frequency * time
        return (
            sin(phase) * 0.58 +
                sin(phase * 2.0) * 0.25 +
                sin(phase * 3.0) * 0.12
            ).toFloat() * envelope
    }
}

class FaerieFanfarePlayer : SynthesizedPlayer(3f, 0.62f) {
    private val notes = listOf(
        1046.50f to 0f,
        1318.51f to 0.09f,
        1567.98f to 0.18f,
        2093.00f to 0.27f,
        2637.02f to 0.36f,
        3135.96f to 0.45f
    )
    private val chord = listOf(1046.50f, 1318.51f, 1567.98f, 2093.00f, 2637.02f)
    private val chordStart = 0.72f

    override fun sample(time: Float): Float {
        var sample = 0f
        notes.forEach { (frequency, start) ->
            if (time >= start) sample += bell(time - start, frequency) * 0.33f
        }
        if (time >= chordStart) {
            chord.forEach { frequency ->
                sample += bell(time - chordStart, frequency) * 0.12f
            }
        }
        return sample
    }

    private fun bell(time: Float, frequency: Float): Float {
        val envelope = min(1f, time / 0.006f) * exp(-2.4f * time)
        val phase = 2.0 * PI * frequency * time
        return (
            sin(phase) * 0.6 +
                sin(phase * 2.01) * 0.24 +
                sin(phase * 3.98) * 0.09
            ).toFloat() * envelope
    }
}
