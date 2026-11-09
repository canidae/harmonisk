package net.exent.harmonisk.audio

internal object SubharmonicCorrector {

    const val BINS_PER_OCTAVE = 60

    private const val SEARCH_RADIUS_BINS = 3

    private const val MAX_STEPS = 1

    const val MIN_PEAK_RATIO = 0.03f

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
