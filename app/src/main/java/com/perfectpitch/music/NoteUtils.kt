package com.perfectpitch.music

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Utilities for converting between frequencies, note names, MIDI numbers, and cents.
 */
@Singleton
class NoteUtils @Inject constructor() {

    companion object {
        val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        const val A4_MIDI_NUMBER = 69
        const val DEFAULT_A4_FREQ = 440.0f
        private const val SEMITONES_PER_OCTAVE = 12
    }

    /**
     * Convert a frequency to the nearest note information.
     *
     * @param frequency Frequency in Hz
     * @param refA4 Reference frequency for A4 (default 440 Hz)
     * @return NoteInfo with name, octave, and cents offset
     */
    fun frequencyToNote(frequency: Float, refA4: Float = DEFAULT_A4_FREQ): NoteInfo {
        if (frequency <= 0f) return NoteInfo("-", 0, 0f, 0)

        // Calculate the number of semitones from A4
        val semitonesFromA4 = 12.0 * log2(frequency.toDouble() / refA4.toDouble())
        val roundedSemitones = round(semitonesFromA4).toInt()
        val centsOff = ((semitonesFromA4 - roundedSemitones) * 100.0).toFloat()

        val midiNumber = A4_MIDI_NUMBER + roundedSemitones
        val noteIndex = ((midiNumber % SEMITONES_PER_OCTAVE) + SEMITONES_PER_OCTAVE) % SEMITONES_PER_OCTAVE
        val octave = (midiNumber / SEMITONES_PER_OCTAVE) - 1

        return NoteInfo(
            name = NOTE_NAMES[noteIndex],
            octave = octave,
            centsOff = centsOff,
            midiNumber = midiNumber
        )
    }

    /**
     * Convert a MIDI note number to frequency.
     *
     * @param midiNumber MIDI note number (0-127)
     * @param refA4 Reference frequency for A4
     * @return Frequency in Hz
     */
    fun midiToFrequency(midiNumber: Int, refA4: Float = DEFAULT_A4_FREQ): Float {
        return refA4 * 2f.pow((midiNumber - A4_MIDI_NUMBER).toFloat() / 12f)
    }

    /**
     * Convert note name and octave to frequency.
     *
     * @param noteName Note name (e.g., "C", "C♯", "D")
     * @param octave Octave number (e.g., 4 for middle C)
     * @param refA4 Reference frequency for A4
     * @return Frequency in Hz, or -1 if note name is invalid
     */
    fun noteToFrequency(noteName: String, octave: Int, refA4: Float = DEFAULT_A4_FREQ): Float {
        val noteIndex = NOTE_NAMES.indexOf(noteName)
        if (noteIndex == -1) return -1f
        val midiNumber = (octave + 1) * SEMITONES_PER_OCTAVE + noteIndex
        return midiToFrequency(midiNumber, refA4)
    }

    /**
     * Calculate the difference in cents between two frequencies.
     */
    fun frequencyToCents(detected: Float, reference: Float): Float {
        if (detected <= 0f || reference <= 0f) return 0f
        return (1200.0 * log2(detected.toDouble() / reference.toDouble())).toFloat()
    }

    /**
     * Get the note name for a MIDI number.
     */
    fun midiToNoteName(midiNumber: Int): String {
        val noteIndex = ((midiNumber % SEMITONES_PER_OCTAVE) + SEMITONES_PER_OCTAVE) % SEMITONES_PER_OCTAVE
        val octave = (midiNumber / SEMITONES_PER_OCTAVE) - 1
        return "${NOTE_NAMES[noteIndex]}$octave"
    }
}

data class NoteInfo(
    val name: String,
    val octave: Int,
    val centsOff: Float,
    val midiNumber: Int
)
