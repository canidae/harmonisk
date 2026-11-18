package net.exent.harmonisk.music

data class Transpose(val octave: Int, val key: Int) {

    val semitones: Int get() = octave * 12 + key

    companion object {
        const val MIN_OCTAVE = -2
        const val MAX_OCTAVE = 2
        const val MIN_KEY = -6
        const val MAX_KEY = 6
        const val MAX_SEMITONES = MAX_OCTAVE * 12 + MAX_KEY

        fun of(octave: Int, key: Int): Transpose = Transpose(
            octave.coerceIn(MIN_OCTAVE, MAX_OCTAVE),
            key.coerceIn(MIN_KEY, MAX_KEY)
        )

        fun fromSemitones(semitones: Int): Transpose {
            val clamped = semitones.coerceIn(-MAX_SEMITONES, MAX_SEMITONES)
            var octave = clamped / 12
            var key = clamped % 12
            if (key > MAX_KEY) {
                key -= 12
                octave++
            } else if (key < MIN_KEY) {
                key += 12
                octave--
            }
            return Transpose(octave, key)
        }
    }
}
