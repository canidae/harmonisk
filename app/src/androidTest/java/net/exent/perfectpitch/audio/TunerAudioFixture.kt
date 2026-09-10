package net.exent.perfectpitch.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sin

/**
 * Audio for tuner tests: synthesised held tones at known frequencies, and a way to hear them back
 * through the device's own microphone so the needle can be measured on a real capture path.
 */
object TunerAudioFixture {

    private const val PI = Math.PI

    /** Slice into capture-sized chunks. */
    fun chunks(audio: FloatArray): List<FloatArray> {
        val size = AudioCaptureService.BUFFER_SIZE_SAMPLES
        return (0 until audio.size / size).map { i ->
            audio.copyOfRange(i * size, (i + 1) * size)
        }
    }

    /**
     * A held instrument-like tone: a harmonic stack with a little broadband noise, plus optional
     * vibrato. Sustained, so needle movement measured on it comes from the detector, the capture
     * path and the filter — not from the player.
     */
    fun tone(
        freqHz: Double,
        seconds: Double,
        vibratoCents: Double = 0.0,
        seed: Long = 11
    ): FloatArray {
        val samples = (seconds * AudioConstants.SAMPLE_RATE).toInt()
        val random = java.util.Random(seed)
        val out = FloatArray(samples)
        var peak = 0.0
        for (i in 0 until samples) {
            val t = i.toDouble() / AudioConstants.SAMPLE_RATE
            val vibrato = if (vibratoCents == 0.0) 0.0
            else vibratoCents * sin(2 * PI * 5.5 * t)
            val f = freqHz * Math.pow(2.0, vibrato / 1200.0)
            var value = 0.0
            for (h in 1..8) value += sin(2 * PI * f * h * t) / h
            value += random.nextGaussian() * 0.01
            out[i] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        val inv = (0.5 / peak).toFloat()
        for (i in out.indices) out[i] *= inv
        return out
    }

    /** Two held tones spliced together, to measure how quickly the needle settles on a new note. */
    fun toneChange(firstHz: Double, secondHz: Double, secondsEach: Double): FloatArray =
        tone(firstHz, secondsEach) + tone(secondHz, secondsEach, seed = 12)

    /**
     * Play [audio] through the speaker and record it back through the microphone, returning the
     * chunks the app's own capture service delivered.
     *
     * This exercises the production path end to end — the real audio source, the real mic, the
     * real room — which is where the detector noise a player actually sees comes from. The played
     * pitch is still known exactly, because the acoustic path does not transpose what it carries.
     */
    fun playAndRecord(context: Context, audio: FloatArray, listenSeconds: Double): List<FloatArray> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.size * Float.SIZE_BYTES)
            .build()

        val service = AudioCaptureService(context)
        val recorded = mutableListOf<FloatArray>()
        try {
            track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
            runBlocking {
                val capture = launch(Dispatchers.IO) {
                    withTimeoutOrNull((listenSeconds * 1000).toLong()) {
                        service.audioFlow().collect { recorded.add(it) }
                    }
                }
                track.play()
                capture.join()
                track.stop()
            }
        } finally {
            track.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        }
        return recorded
    }
}
