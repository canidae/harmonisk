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

class AdventureFanfarePlayer {
    private var track: AudioTrack? = null

    fun play() {
        release()
        val samples = buildSamples()
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
        audio.setVolume(0.75f)
        audio.play()
        track = audio
    }

    fun release() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private fun buildSamples(): ShortArray {
        val count = (SAMPLE_RATE * DURATION).toInt()
        val random = Random(11)
        val drumEnd = 1.35f
        val fanfareStart = 1.35f
        val melody = listOf(
            523.25f to 0f,
            659.25f to 0.19f,
            783.99f to 0.38f,
            1046.50f to 0.57f
        )
        return ShortArray(count) { index ->
            val time = index.toFloat() / SAMPLE_RATE
            var sample = 0f
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
            (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
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

    private companion object {
        const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
        const val DURATION = 3.3f
    }
}
