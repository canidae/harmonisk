package com.perfectpitch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

class MetronomePlayer {

    companion object {
        private const val TAG = "MetronomePlayer"
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_FRAMES = 256
        private const val CLICK_SAMPLES = 88
        private const val CLICK_FREQ = 3000f
        private const val CLICK_FREQ_ACCENT = 4500f

        /** Floor division: rounds toward negative infinity. */
        private fun floorDiv(a: Long, b: Long): Long {
            var q = a / b
            if (a xor b < 0L && q * b != a) q--
            return q
        }
    }

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    @Volatile private var armed = false
    @Volatile private var playing = false
    @Volatile private var bpm: Float = 120f
    @Volatile private var beatsPerBar: Int = 4
    private val sampleCount = AtomicLong(0L)

    fun open() {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(BUFFER_FRAMES * 2, minBufferSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack = track
        track.play()
        running = true

        renderThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(BUFFER_FRAMES)

            while (running) {
                renderBuffer(buffer)
                try {
                    track.write(buffer, 0, buffer.size)
                } catch (_: Exception) {}
            }
        }, "MetronomeRenderThread").apply { start() }

        Log.d(TAG, "Metronome opened")
    }

    fun setArmed(armed: Boolean) {
        this.armed = armed
        if (!armed) playing = false
    }

    fun startSync(positionMs: Long, bpm: Float, beatsPerBar: Int) {
        this.bpm = bpm
        this.beatsPerBar = beatsPerBar
        sampleCount.set(positionMs * SAMPLE_RATE / 1000)
        this.playing = armed
    }

    fun syncPosition(positionMs: Long, bpm: Float, beatsPerBar: Int) {
        this.bpm = bpm
        this.beatsPerBar = beatsPerBar
        val expectedSamples = positionMs * SAMPLE_RATE / 1000
        val drift = kotlin.math.abs(expectedSamples - sampleCount.get())
        if (drift > (SAMPLE_RATE / 5)) {
            sampleCount.set(expectedSamples + CLICK_SAMPLES)
        }
    }

    fun stopSync() {
        playing = false
    }

    fun close() {
        running = false
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {}
        renderThread = null
        armed = false
        playing = false

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing metronome", e)
        }
        audioTrack = null
    }

    private fun renderBuffer(buffer: ShortArray) {
        if (!playing) {
            buffer.fill(0)
            sampleCount.addAndGet(buffer.size.toLong())
            return
        }
        val intervalMs = 60000.0 / bpm
        if (intervalMs <= 0) {
            buffer.fill(0)
            sampleCount.addAndGet(buffer.size.toLong())
            return
        }

        val samplesPerMs = SAMPLE_RATE / 1000.0
        val baseSample = sampleCount.get()
        val baseTimeMs = baseSample / samplesPerMs
        val beatIntervalSamples = (intervalMs * samplesPerMs).toLong()

        for (i in buffer.indices) {
            val absSample = baseSample + i
            val beatIndex = if (beatIntervalSamples > 0) floorDiv(absSample, beatIntervalSamples) else 0L
            val sampleInBeat = (absSample - beatIndex * beatIntervalSamples).toInt()

            if (sampleInBeat < CLICK_SAMPLES) {
                val isAccent = beatsPerBar > 0 && (beatIndex % beatsPerBar) == 0L
                val clickFreq = if (isAccent) CLICK_FREQ_ACCENT else CLICK_FREQ
                val vol = if (isAccent) 0.7f else 0.4f
                val t = sampleInBeat.toFloat() / SAMPLE_RATE
                val envelope = 1f - sampleInBeat.toFloat() / CLICK_SAMPLES
                val sample = sin(2.0 * PI * clickFreq * t).toFloat() * envelope * vol
                buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            } else {
                buffer[i] = 0
            }
        }
        sampleCount.addAndGet(buffer.size.toLong())
    }
}
