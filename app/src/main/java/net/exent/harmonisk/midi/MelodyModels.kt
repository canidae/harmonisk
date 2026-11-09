package net.exent.harmonisk.midi

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

data class KeySignature(val fifths: Int, val isMinor: Boolean) {

    val usesFlats: Boolean get() = fifths < 0

    fun transpose(semitones: Int): KeySignature {
        if (semitones == 0) return this
        val index = (fifths + 7).coerceIn(0, 14)
        val tonic = (if (isMinor) MINOR_TONIC_PC[index] else MAJOR_TONIC_PC[index])
        val transposed = ((tonic + semitones) % 12 + 12) % 12
        val newFifths = if (isMinor) MINOR_FIFTHS[transposed] else MAJOR_FIFTHS[transposed]
        return KeySignature(newFifths, isMinor)
    }

    companion object {
        private val MAJOR_TONIC_PC = intArrayOf(11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1)

        private val MINOR_TONIC_PC = intArrayOf(8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        private val MAJOR_FIFTHS = intArrayOf(0, 7, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)

        private val MINOR_FIFTHS = intArrayOf(-3, 4, -1, -6, 1, -4, 3, -2, -7, 0, -5, 2)

        fun of(tonicPitchClass: Int, isMinor: Boolean): KeySignature {
            val pc = ((tonicPitchClass % 12) + 12) % 12
            return KeySignature(if (isMinor) MINOR_FIFTHS[pc] else MAJOR_FIFTHS[pc], isMinor)
        }

        fun keyAlteration(pitchClass: Int, fifths: Int): Int {
            val pc = ((pitchClass % 12) + 12) % 12
            for (i in 0 until fifths.coerceAtMost(7)) if (pc == SHARP_ORDER[i]) return 1
            for (i in 0 until (-fifths).coerceAtMost(7)) if (pc == FLAT_ORDER[i]) return -1
            return 0
        }

        private val SHARP_ORDER = intArrayOf(5, 0, 7, 2, 9, 4, 11)

        private val FLAT_ORDER = intArrayOf(11, 4, 9, 2, 7, 0, 5)
    }
}
