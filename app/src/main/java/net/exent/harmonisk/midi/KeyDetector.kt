package net.exent.harmonisk.midi

/**
 * Estimates the key of a melody from its pitch content with the Krumhansl-Schmuckler
 * algorithm: a duration-weighted pitch-class histogram is correlated with the major and
 * minor key profiles, and the best-correlating key wins. Used only when the file itself
 * carries no key, so a notated key is never second-guessed.
 */
object KeyDetector {

    /** Fewer notes than this cannot say anything useful about a key. */
    private const val MIN_NOTES = 6

    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    fun detect(notes: List<NoteEvent>): KeySignature? {
        if (notes.size < MIN_NOTES) return null
        val histogram = DoubleArray(12)
        for (note in notes) {
            val pc = ((note.noteNumber % 12) + 12) % 12
            histogram[pc] += note.durationTicks.coerceAtLeast(1).toDouble()
        }
        if (histogram.sum() <= 0.0) return null

        var best: KeySignature? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (tonic in 0 until 12) {
            for (isMinor in booleanArrayOf(false, true)) {
                val profile = if (isMinor) MINOR_PROFILE else MAJOR_PROFILE
                val score = correlation(histogram) { pc -> profile[(pc - tonic + 12) % 12] }
                if (score > bestScore) {
                    bestScore = score
                    best = KeySignature.of(tonic, isMinor)
                }
            }
        }
        return best
    }

    /** Pearson correlation between the histogram and a key profile. */
    private inline fun correlation(histogram: DoubleArray, profile: (Int) -> Double): Double {
        val values = DoubleArray(12) { profile(it) }
        val meanHistogram = histogram.average()
        val meanProfile = values.average()
        var covariance = 0.0
        var varianceHistogram = 0.0
        var varianceProfile = 0.0
        for (i in 0 until 12) {
            val h = histogram[i] - meanHistogram
            val p = values[i] - meanProfile
            covariance += h * p
            varianceHistogram += h * h
            varianceProfile += p * p
        }
        val denominator = kotlin.math.sqrt(varianceHistogram * varianceProfile)
        return if (denominator <= 0.0) Double.NEGATIVE_INFINITY else covariance / denominator
    }
}
