package net.exent.harmonisk.audio

/**
 * Picks the true fundamental out of CREPE's activation vector when the model ranks a harmonic
 * above it.
 *
 * CREPE's bins are evenly spaced in cents (7180 cents over 359 intervals = 20 cents per bin),
 * so halving the frequency is exactly [BINS_PER_OCTAVE] bins down. Instruments with a weak
 * fundamental — a violin G string heard through a phone microphone's high-pass is the typical
 * case — make the model put its top peak on the 2nd harmonic while a smaller but real peak
 * remains an octave below. Descending to that peak recovers the octave without disturbing the
 * model's cents precision.
 */
internal object SubharmonicCorrector {

    /** 1200 cents / (7180 cents / 359 intervals). */
    const val BINS_PER_OCTAVE = 60

    /** How far from the exact octave a subharmonic peak may sit and still count. */
    private const val SEARCH_RADIUS_BINS = 3

    /** Octaves the correction may descend. One is enough for a peak on the 2nd harmonic, and a
     *  deeper walk risks dropping a further octave on noisy frames. */
    private const val MAX_STEPS = 1

    /** A subharmonic peak must reach this fraction of its parent peak's activation. */
    const val MIN_PEAK_RATIO = 0.05f

    /**
     * @return The bin of the lowest octave-related peak that is strong enough to be the
     *         fundamental, or [peakBin] when no such peak exists.
     */
    fun fundamentalBin(activation: FloatArray, peakBin: Int): Int {
        var bin = peakBin
        repeat(MAX_STEPS) {
            val parent = activation[bin]
            val sub = localPeakNear(activation, bin - BINS_PER_OCTAVE) ?: return bin
            if (activation[sub] < parent * MIN_PEAK_RATIO) return bin
            bin = sub
        }
        return bin
    }

    private fun localPeakNear(activation: FloatArray, center: Int): Int? {
        if (center < 1 || center > activation.size - 2) return null
        var best = -1
        var bestValue = 0f
        val start = maxOf(1, center - SEARCH_RADIUS_BINS)
        val end = minOf(activation.size - 2, center + SEARCH_RADIUS_BINS)
        for (i in start..end) {
            val value = activation[i]
            if (value > activation[i - 1] && value >= activation[i + 1] && value > bestValue) {
                best = i
                bestValue = value
            }
        }
        return if (best >= 0) best else null
    }
}
