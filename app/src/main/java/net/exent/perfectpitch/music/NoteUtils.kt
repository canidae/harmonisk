package net.exent.perfectpitch.music

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

class NoteUtils {

    companion object {
        val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        const val A4_MIDI_NUMBER = 69
        const val DEFAULT_A4_FREQ = 440.0f
        private const val SEMITONES_PER_OCTAVE = 12

        const val PERFECT_CENTS = 10f
        const val GREAT_CENTS = 20f
        const val GOOD_CENTS = 30f
        const val OK_CENTS = 40f

        fun midiToFrequency(midiNumber: Int, refA4: Float = DEFAULT_A4_FREQ): Float =
            (refA4.toDouble() * 2.0.pow((midiNumber - A4_MIDI_NUMBER).toDouble() / 12.0)).toFloat()
    }

    fun frequencyToNote(frequency: Float, refA4: Float = DEFAULT_A4_FREQ): NoteInfo {
        if (frequency <= 0f) return NoteInfo("-", 0, 0f, 0)

        val semitonesFromA4 = 12.0 * log2(frequency.toDouble() / refA4.toDouble())
        val roundedSemitones = round(semitonesFromA4).toInt()
        val centsOff = ((semitonesFromA4 - roundedSemitones) * 100.0).toFloat()

        val midiNumber = A4_MIDI_NUMBER + roundedSemitones
        val (noteIndex, octave) = midiToNoteIndexAndOctave(midiNumber)

        return NoteInfo(name = NOTE_NAMES[noteIndex], octave = octave, centsOff = centsOff, midiNumber = midiNumber)
    }

    fun noteToFrequency(noteName: String, octave: Int, refA4: Float = DEFAULT_A4_FREQ): Float {
        val noteIndex = NOTE_NAMES.indexOf(noteName)
        if (noteIndex == -1) return -1f
        return midiToFrequency((octave + 1) * SEMITONES_PER_OCTAVE + noteIndex, refA4)
    }

    fun frequencyToCents(detected: Float, reference: Float): Float {
        if (detected <= 0f || reference <= 0f) return 0f
        return (1200.0 * log2(detected.toDouble() / reference.toDouble())).toFloat()
    }

    fun midiToNoteName(midiNumber: Int): String {
        val (noteIndex, octave) = midiToNoteIndexAndOctave(midiNumber)
        return "${NOTE_NAMES[noteIndex]}$octave"
    }

    private fun midiToNoteIndexAndOctave(midi: Int): Pair<Int, Int> {
        val noteIndex = ((midi % SEMITONES_PER_OCTAVE) + SEMITONES_PER_OCTAVE) % SEMITONES_PER_OCTAVE
        val octave = (midi / SEMITONES_PER_OCTAVE) - 1
        return noteIndex to octave
    }
}
