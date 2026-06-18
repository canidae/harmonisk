package net.exent.perfectpitch.audio

import android.util.Log
import net.exent.perfectpitch.BuildConfig
import net.exent.perfectpitch.music.NoteUtils
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesizer that generates polyphonic tones with per-voice harmonic profiles.
 *
 * Supports polyphony (multiple simultaneous notes per voice), a simple ADSR envelope
 * to avoid clicks on note-on/off transitions, and 8 distinct voice timbres defined by
 * harmonic amplitude profiles.
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
        private const val NUM_VOICES = 8
        private const val MIDI_NOTE_MASK = 0x7F

        private val ATTACK_DELTA = 1f / (ATTACK_MS / 1000f * SAMPLE_RATE)
        private val DECAY_DELTA = (1f - SUSTAIN_LEVEL) / (DECAY_MS / 1000f * SAMPLE_RATE)
        private val RELEASE_DELTA = 1f / (RELEASE_MS / 1000f * SAMPLE_RATE)
        private val PHASE_INC = 2.0 * PI / SAMPLE_RATE

        /** Distinct harmonic amplitude profiles for each voice (indices 0-7). */
        private val VOICE_PROFILES = listOf(
            // Voice 0 — Piano-like (focused fundamental, gentle upper partials)
            floatArrayOf(1.0f, 0.3f, 0.1f, 0.05f, 0.02f, 0.01f),
            // Voice 1 — Organ-like (rich, even harmonic distribution)
            floatArrayOf(1.0f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f),
            // Voice 2 — Reed-like (odd harmonics emphasized)
            floatArrayOf(1.0f, 0.2f, 0.5f, 0.1f, 0.3f, 0.05f),
            // Voice 3 — Brass-like (strong bright harmonics)
            floatArrayOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f, 0.1f),
            // Voice 4 — String-like (thin, airy)
            floatArrayOf(1.0f, 0.1f, 0.25f, 0.05f, 0.15f, 0.03f),
            // Voice 5 — Harpsichord-like (plucky, strong 2nd & 4th)
            floatArrayOf(1.0f, 0.6f, 0.2f, 0.3f, 0.1f, 0.15f),
            // Voice 6 — Warm pad (smooth roll-off)
            floatArrayOf(1.0f, 0.4f, 0.35f, 0.2f, 0.1f, 0.08f),
            // Voice 7 — Clavi-like (piercing upper harmonics)
            floatArrayOf(1.0f, 0.7f, 0.1f, 0.4f, 0.05f, 0.2f)
        )

        /** Normalisation divisors, pre-computed so each profile sums to 1.0 */
        private val VOICE_NORM = VOICE_PROFILES.map { profile -> profile.sum().coerceAtLeast(0.01f) }

        private fun noteKey(channel: Int, note: Int): Int = (channel shl 7) or (note and MIDI_NOTE_MASK)
    }

    private val activeNotes = mutableMapOf<Int, NoteState>()
    private val noteLock = Any()

    override val isReady: Boolean get() = renderThread.running

    private class NoteState(
        val frequency: Float,
        val velocity: Float,
        val profileIndex: Int,
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Synthesizer opened")
    }

    override fun noteOn(channel: Int, note: Int, velocity: Int) {
        val freq = NoteUtils.midiToFrequency(note)
        val vel = (velocity.coerceIn(0, 127)) / 127f
        val profileIndex = (channel % NUM_VOICES).coerceAtLeast(0)
        val key = noteKey(channel, note)
        synchronized(noteLock) {
            activeNotes[key] = NoteState(
                frequency = freq,
                velocity = vel,
                profileIndex = profileIndex,
                phase = activeNotes[key]?.phase ?: 0.0,
                envelope = 0f,
                stage = EnvelopeStage.ATTACK,
                releasing = false
            )
        }
    }

    override fun noteOff(channel: Int, note: Int) {
        val key = noteKey(channel, note)
        synchronized(noteLock) {
            activeNotes[key]?.let {
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Synthesizer closed")
    }

    private fun renderBuffer(buffer: ShortArray) {
        val toRemove = mutableListOf<Int>()

        synchronized(noteLock) {
            for (i in buffer.indices) {
                var sample = 0f
                for ((key, state) in activeNotes) {
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
                        EnvelopeStage.SUSTAIN -> { /* envelope held at SUSTAIN_LEVEL */ }
                        EnvelopeStage.RELEASE -> {
                            state.envelope -= RELEASE_DELTA
                            if (state.envelope <= 0f) {
                                state.envelope = 0f
                                state.stage = EnvelopeStage.DONE
                                toRemove.add(key)
                            }
                        }
                        EnvelopeStage.DONE -> {
                            toRemove.add(key)
                        }
                    }

                    val harmonics = VOICE_PROFILES[state.profileIndex]
                    var raw = 0f
                    for (h in harmonics.indices) {
                        val partial = h + 1
                        val amp = harmonics[h]
                        if (amp > 0f) {
                            raw += sin(state.phase * state.frequency * partial).toFloat() * amp
                        }
                    }
                    raw /= VOICE_NORM[state.profileIndex]

                    sample += raw * state.envelope * state.velocity
                    state.phase += PHASE_INC
                }

                val clipped = sample.coerceIn(-1f, 1f)
                buffer[i] = (clipped * Short.MAX_VALUE).toInt().toShort()
            }
            toRemove.forEach { activeNotes.remove(it) }
        }
    }

}
