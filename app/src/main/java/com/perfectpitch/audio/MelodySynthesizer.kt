package com.perfectpitch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesizer that generates piano-like tones using [AudioTrack] and sine waves.
 *
 * Supports polyphony (multiple simultaneous notes) and a simple ADSR envelope
 * to avoid clicks on note-on/off transitions.
 *
 * Usage:
 * ```
 * val synth = MelodySynthesizer()
 * synth.open()
 * synth.noteOn(channel = 0, note = 60, velocity = 100)
 * delay(500)
 * synth.noteOff(channel = 0, note = 60)
 * synth.close()
 * ```
 */
class MelodySynthesizer {

    companion object {
        private const val TAG = "MelodySynthesizer"
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_FRAMES = 512
        private const val ATTACK_MS = 10f
        private const val DECAY_MS = 50f
        private const val SUSTAIN_LEVEL = 0.7f
        private const val RELEASE_MS = 30f
    }

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    private val activeNotes = mutableMapOf<Int, NoteState>()
    private val noteLock = Any()

    val isReady: Boolean get() = audioTrack != null && running

    private data class NoteState(
        val frequency: Float,
        val velocity: Float,
        var phase: Double = 0.0,
        var envelope: Float = 0f,
        var stage: EnvelopeStage = EnvelopeStage.ATTACK,
        var releasing: Boolean = false
    )

    private enum class EnvelopeStage { ATTACK, DECAY, SUSTAIN, RELEASE, DONE }

    /**
     * Create the AudioTrack and start the rendering thread.
     * No special permissions or hardware required — works on all Android devices.
     *
     * @param context Unused — kept for API compatibility with callers.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun open(context: android.content.Context? = null) {
        if (isReady) return

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BUFFER_FRAMES * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()
        running = true

        renderThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(BUFFER_FRAMES)
            while (running) {
                renderBuffer(buffer)
                track.write(buffer, 0, buffer.size)
            }
        }, "SynthRenderThread").apply { start() }

        Log.d(TAG, "Sine wave synthesizer opened")
    }

    fun noteOn(channel: Int, note: Int, velocity: Int) {
        val freq = midiToFrequency(note)
        val vel = (velocity.coerceIn(0, 127)) / 127f
        synchronized(noteLock) {
            activeNotes[note] = NoteState(
                frequency = freq,
                velocity = vel,
                phase = activeNotes[note]?.phase ?: 0.0,
                envelope = 0f,
                stage = EnvelopeStage.ATTACK,
                releasing = false
            )
        }
    }

    fun noteOff(channel: Int, note: Int) {
        synchronized(noteLock) {
            activeNotes[note]?.let {
                it.releasing = true
                it.stage = EnvelopeStage.RELEASE
            }
        }
    }

    fun allNotesOff(channel: Int = 0) {
        synchronized(noteLock) {
            activeNotes.values.forEach {
                it.releasing = true
                it.stage = EnvelopeStage.RELEASE
            }
        }
    }

    fun close() {
        running = false
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {}
        renderThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing AudioTrack", e)
        }
        audioTrack = null

        synchronized(noteLock) { activeNotes.clear() }
        Log.d(TAG, "Synthesizer closed")
    }

    private fun renderBuffer(buffer: ShortArray) {
        val attackDelta = 1f / (ATTACK_MS / 1000f * SAMPLE_RATE)
        val decayDelta = (1f - SUSTAIN_LEVEL) / (DECAY_MS / 1000f * SAMPLE_RATE)
        val releaseDelta = 1f / (RELEASE_MS / 1000f * SAMPLE_RATE)
        val phaseInc = 2.0 * PI / SAMPLE_RATE

        val toRemove = mutableListOf<Int>()

        for (i in buffer.indices) {
            var sample = 0f
            synchronized(noteLock) {
                for ((note, state) in activeNotes) {
                    // Advance envelope
                    when (state.stage) {
                        EnvelopeStage.ATTACK -> {
                            state.envelope += attackDelta
                            if (state.envelope >= 1f) {
                                state.envelope = 1f
                                state.stage = EnvelopeStage.DECAY
                            }
                        }
                        EnvelopeStage.DECAY -> {
                            state.envelope -= decayDelta
                            if (state.envelope <= SUSTAIN_LEVEL) {
                                state.envelope = SUSTAIN_LEVEL
                                state.stage = EnvelopeStage.SUSTAIN
                            }
                        }
                        EnvelopeStage.SUSTAIN -> {}
                        EnvelopeStage.RELEASE -> {
                            state.envelope -= releaseDelta
                            if (state.envelope <= 0f) {
                                state.envelope = 0f
                                state.stage = EnvelopeStage.DONE
                                toRemove.add(note)
                            }
                        }
                        EnvelopeStage.DONE -> {
                            toRemove.add(note)
                        }
                    }

                    // Generate sample: fundamental + 2nd harmonic for richer tone
                    val fundamental = sin(state.phase * state.frequency).toFloat()
                    val harmonic2 = sin(state.phase * state.frequency * 2.0).toFloat() * 0.3f
                    val raw = (fundamental + harmonic2) / 1.3f

                    sample += raw * state.envelope * state.velocity
                    state.phase += phaseInc
                }
                toRemove.forEach { activeNotes.remove(it) }
                toRemove.clear()
            }

            // Soft clip and convert to 16-bit
            val clipped = sample.coerceIn(-1f, 1f)
            buffer[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun midiToFrequency(note: Int): Float {
        return 440f * 2f.pow((note - 69) / 12f)
    }
}
