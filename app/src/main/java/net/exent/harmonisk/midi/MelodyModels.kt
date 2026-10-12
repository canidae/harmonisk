package net.exent.harmonisk.midi

/**
 * Data classes representing parsed MIDI events.
 */
data class NoteEvent(
    val noteNumber: Int,
    val velocity: Int,
    val startTick: Long,
    val durationTicks: Long
) {
    val endTick: Long get() = startTick + durationTicks
}

data class TempoEvent(
    val tick: Long,
    val microsecondsPerBeat: Long
) {
    val bpm: Double get() = 60_000_000.0 / microsecondsPerBeat
}

data class TimeSignatureEvent(
    val tick: Long,
    val numerator: Int,
    val denominator: Int
)

/**
 * A key signature in the MIDI/MusicXML convention: [fifths] counts sharps (positive) or
 * flats (negative) in the range -7..7, and [isMinor] picks the mode. A key is fixed for the
 * whole track, so a piece that changes key mid-way uses its opening key.
 */
data class KeySignature(val fifths: Int, val isMinor: Boolean) {

    /** Flat keys are spelled with flats, sharp and natural keys with sharps. */
    val usesFlats: Boolean get() = fifths < 0

    /** The same key heard [semitones] higher, respelled for the new tonic. */
    fun transpose(semitones: Int): KeySignature {
        if (semitones == 0) return this
        val index = (fifths + 7).coerceIn(0, 14)
        val tonic = (if (isMinor) MINOR_TONIC_PC[index] else MAJOR_TONIC_PC[index])
        val transposed = ((tonic + semitones) % 12 + 12) % 12
        val newFifths = if (isMinor) MINOR_FIFTHS[transposed] else MAJOR_FIFTHS[transposed]
        return KeySignature(newFifths, isMinor)
    }

    companion object {
        /** Major tonics by fifths, -7..7 (Cb … C#). */
        private val MAJOR_TONIC_PC = intArrayOf(11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1)

        /** Minor tonics by fifths, -7..7 (Ab … A#). */
        private val MINOR_TONIC_PC = intArrayOf(8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        /** Fifths by pitch class (C … B) for major keys. */
        private val MAJOR_FIFTHS = intArrayOf(0, 7, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)

        /** Fifths by pitch class (C … B) for minor keys. */
        private val MINOR_FIFTHS = intArrayOf(-3, 4, -1, -6, 1, -4, 3, -2, -7, 0, -5, 2)

        fun of(tonicPitchClass: Int, isMinor: Boolean): KeySignature {
            val pc = ((tonicPitchClass % 12) + 12) % 12
            return KeySignature(if (isMinor) MINOR_FIFTHS[pc] else MAJOR_FIFTHS[pc], isMinor)
        }

        /** The accidental a key signature gives a pitch class: +1 sharp, -1 flat, 0 natural. */
        fun keyAlteration(pitchClass: Int, fifths: Int): Int {
            val pc = ((pitchClass % 12) + 12) % 12
            for (i in 0 until fifths.coerceAtMost(7)) if (pc == SHARP_ORDER[i]) return 1
            for (i in 0 until (-fifths).coerceAtMost(7)) if (pc == FLAT_ORDER[i]) return -1
            return 0
        }

        /** Pitch classes sharpened by a key signature, in the order they gain sharps. */
        private val SHARP_ORDER = intArrayOf(5, 0, 7, 2, 9, 4, 11)

        /** Pitch classes flattened by a key signature, in the order they gain flats. */
        private val FLAT_ORDER = intArrayOf(11, 4, 9, 2, 7, 0, 5)
    }
}
