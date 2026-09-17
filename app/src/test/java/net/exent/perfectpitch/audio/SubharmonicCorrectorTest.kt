package net.exent.perfectpitch.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SubharmonicCorrectorTest {

    private fun activation(vararg peaks: Pair<Int, Float>): FloatArray {
        val act = FloatArray(360)
        for ((bin, value) in peaks) act[bin] = value
        return act
    }

    @Test
    fun descendsToHalfFrequencyPeak() {
        val act = activation(200 to 0.8f, 140 to 0.25f)
        assertEquals(140, SubharmonicCorrector.fundamentalBin(act, 200))
    }

    @Test
    fun keepsPeakWhenNoSubharmonicPeakExists() {
        val act = activation(200 to 0.8f, 140 to 0.01f)
        assertEquals(200, SubharmonicCorrector.fundamentalBin(act, 200))
    }

    @Test
    fun descendsOnlyOneOctave() {
        val act = activation(260 to 0.9f, 200 to 0.8f, 140 to 0.7f)
        assertEquals(200, SubharmonicCorrector.fundamentalBin(act, 260))
    }

    @Test
    fun stopsWhenSubharmonicIsBelowRatio() {
        val act = activation(200 to 0.8f, 140 to 0.03f)
        assertEquals(200, SubharmonicCorrector.fundamentalBin(act, 200))
    }

    @Test
    fun requiresALocalMaximumAtTheSubharmonic() {
        // A monotonic slope has no peak an octave down, so the top peak stands.
        val act = FloatArray(360) { if (it <= 200) it / 200f else 1f }
        assertEquals(200, SubharmonicCorrector.fundamentalBin(act, 200))
    }

    @Test
    fun findsSubharmonicSlightlyOffTheExactOctave() {
        val act = activation(200 to 0.8f, 197 to 0.0f, 142 to 0.3f)
        assertEquals(142, SubharmonicCorrector.fundamentalBin(act, 200))
    }

    @Test
    fun ignoresSubharmonicOutsideSearchRadius() {
        val act = activation(200 to 0.8f, 130 to 0.5f)
        assertEquals(200, SubharmonicCorrector.fundamentalBin(act, 200))
    }

    @Test
    fun stopsWhenTheOctaveIsBelowTheLowestBin() {
        val act = activation(40 to 0.6f, 30 to 0.5f)
        assertEquals(40, SubharmonicCorrector.fundamentalBin(act, 40))
    }
}
