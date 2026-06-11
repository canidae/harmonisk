package net.exent.perfectpitch.audio

import android.util.Log
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesizer that generates piano-like tones using [AudioTrack] and sine waves.
 *
 * Supports polyphony (multiple simultaneous notes) and a simple ADSR envelope
 * to avoid clicks on note-on/off transitions.
 */
class MelodySynthesizer : NotePlayer {

    companion object {
        private const val TAG = "MelodySynthesizer"
        private const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
        private const val BUFFER_FRAMES = 512
        private const val ATTACK_MS = 10f
        private const val DECAY_MS = 50f
        private const val SUSTAIN_LEVEL = 0.7f
        private const val RELEASE_MS = 30f

        private val ATTACK_DELTA = 1f / (ATTACK_MS / 1000f * SAMPLE_RATE)
        private val DECAY_DELTA = (1f - SUSTAIN_LEVEL) / (DECAY_MS / 1000f * SAMPLE_RATE)
        private val RELEASE_DELTA = 1f / (RELEASE_MS / 1000f * SAMPLE_RATE)
        private val PHASE_INC = 2.0 * PI / SAMPLE_RATE
    }

    private val activeNotes = mutableMapOf<Int, NoteState>()
    private val noteLock = Any()

    override val isReady: Boolean get() = renderThread.running

    private class NoteState(
        val frequency: Float,
        val velocity: Float,
        var phase: Double = 0.0,
        var envelope: Float = 0f,
        var stage: EnvelopeStage = EnvelopeStage.ATTACK,
        var releasing: Boolean = false
    )

    private enum class EnvelopeStage { ATTACK, DECAY, SUSTAIN, RELEASE, DONE }

    private val renderThread = AudioRenderThread(
        threadName = "SynthRenderThread",
        contentType = android.media.AudioAttributes.CONTENT_TYPE_MUSIC,
        bufferFrames = BUFFER_FRAMES,
        render = ::renderBuffer
    )

    fun open() {
        renderThread.open()
        Log.d(TAG, "Sine wave synthesizer opened")
    }

    override fun noteOn(channel: Int, note: Int, velocity: Int) {
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

    override fun noteOff(channel: Int, note: Int) {
        synchronized(noteLock) {
            activeNotes[note]?.let {
                it.releasing = true
                it.stage = EnvelopeStage.RELEASE
            }
        }
    }

    override fun allNotesOff() {
        synchronized(noteLock) {
            activeNotes.values.forEach {
                it.releasing = true
                it.stage = EnvelopeStage.RELEASE
            }
        }
    }

    fun close() {
        renderThread.close()
        synchronized(noteLock) { activeNotes.clear() }
        Log.d(TAG, "Synthesizer closed")
    }

    private fun renderBuffer(buffer: ShortArray) {
        val toRemove = mutableListOf<Int>()

        synchronized(noteLock) {
            for (i in buffer.indices) {
                var sample = 0f
                for ((note, state) in activeNotes) {
                    when (state.stage) {
                        EnvelopeStage.ATTACK -> {
                            state.envelope += ATTACK_DELTA
                            if (state.envelope >= 1f) {
                                state.envelope = 1f
                                state.stage = EnvelopeStage.DECAY
                            }
                        }
                        EnvelopeStage.DECAY -> {
                            state.envelope -= DECAY_DELTA
                            if (state.envelope <= SUSTAIN_LEVEL) {
                                state.envelope = SUSTAIN_LEVEL
                                state.stage = EnvelopeStage.SUSTAIN
                            }
                        }
                        EnvelopeStage.SUSTAIN -> { /* envelope held at SUSTAIN_LEVEL; no per-sample update needed */ }
                        EnvelopeStage.RELEASE -> {
                            state.envelope -= RELEASE_DELTA
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
                    state.phase += PHASE_INC
                }

                val clipped = sample.coerceIn(-1f, 1f)
                buffer[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
            }
            toRemove.forEach { activeNotes.remove(it) }
        }
    }

    private fun midiToFrequency(note: Int): Float {
        return 440f * 2f.pow((note - 69) / 12f)
    }
}
