package net.exent.perfectpitch.audio

import android.media.AudioAttributes
import android.util.Log
import net.exent.perfectpitch.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

class MetronomePlayer {

    companion object {
        private const val TAG = "MetronomePlayer"
        private const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
        private const val BUFFER_FRAMES = 256
        private const val CLICK_SAMPLES = 88
        private const val CLICK_FREQ = 3000f
        private const val CLICK_FREQ_ACCENT = 4500f
    }

    @Volatile private var armed = false
    @Volatile private var playing = false
    @Volatile private var bpm: Float = 120f
    @Volatile private var beatsPerBar: Int = 4
    private val sampleCount = AtomicLong(0L)

    private val renderThread = AudioRenderThread(
        threadName = "MetronomeRenderThread",
        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        bufferFrames = BUFFER_FRAMES,
        render = ::renderBuffer
    )

    fun open() {
        renderThread.open()
        if (BuildConfig.DEBUG) Log.d(TAG, "Metronome opened")
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
        renderThread.close()
        armed = false
        playing = false
        if (BuildConfig.DEBUG) Log.d(TAG, "Metronome closed")
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
            val beatIndex = if (beatIntervalSamples > 0) Math.floorDiv(absSample, beatIntervalSamples) else 0L
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
