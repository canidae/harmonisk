package net.exent.harmonisk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Behavioural contract of [PitchStabilizer]: kill needle wobble, never introduce a reading
 * error, and stay quick when the player actually changes pitch.
 */
class PitchStabilizerTest {

    private companion object {
        /** Frame interval matching the real capture hop plus detector cost on the test device. */
        const val DT = 0.0185

        const val CENTS_PER_SEMITONE = 100.0

        fun semitones(cents: Double) = cents / CENTS_PER_SEMITONE
    }

    private fun feed(
        stabilizer: PitchStabilizer,
        frames: Int,
        dt: Double = DT,
        value: (Int) -> Pair<Double, Float>
    ): DoubleArray {
        val out = DoubleArray(frames)
        for (i in 0 until frames) {
            val (cents, confidence) = value(i)
            out[i] = stabilizer.accept(semitones(cents), confidence, dt) * CENTS_PER_SEMITONE
        }
        return out
    }

    private fun std(values: DoubleArray, from: Int = 0): Double {
        val slice = values.toList().subList(from, values.size)
        val mean = slice.average()
        return sqrt(slice.sumOf { (it - mean) * (it - mean) } / slice.size)
    }

    private fun mean(values: DoubleArray, from: Int = 0): Double =
        values.toList().subList(from, values.size).average()

    @Test
    fun `steady tone reads the true pitch after noise is filtered`() {
        val stabilizer = PitchStabilizer()
        val random = Random(1)
        val trueCents = 12.0

        val out = feed(stabilizer, 400) { trueCents + random.nextGaussian() * 10.0 to 0.95f }

        assertEquals("filtered reading must not be biased", trueCents, mean(out, 200), 1.0)
    }

    @Test
    fun `needle wobble is cut by more than a factor of three`() {
        val stabilizer = PitchStabilizer()
        val random = Random(2)
        val trueCents = -18.0

        val raw = DoubleArray(400) { trueCents + random.nextGaussian() * 10.0 }
        val out = feed(stabilizer, 400) { raw[it] to 0.95f }

        val rawStd = std(raw, 200)
        val filteredStd = std(out, 200)
        assertTrue(
            "expected wobble ${"%.2f".format(filteredStd)}ct to be well below raw ${"%.2f".format(rawStd)}ct",
            filteredStd < rawStd / 3
        )
    }

    @Test
    fun `isolated detector spikes do not move the needle`() {
        val stabilizer = PitchStabilizer()
        val random = Random(3)
        val trueCents = 5.0

        // One badly wrong frame (an octave slip) surrounded by otherwise steady input.
        val out = feed(stabilizer, 200) { i ->
            when {
                i == 150 -> 100.0 to 0.9f
                else -> trueCents + random.nextGaussian() * 5.0 to 0.95f
            }
        }

        assertTrue(
            "a single spike moved the needle by ${"%.1f".format(abs(out[150] - trueCents))}ct",
            abs(out[150] - trueCents) < 5.0
        )
    }

    @Test
    fun `a real note change is followed within a quarter second`() {
        val stabilizer = PitchStabilizer()
        val framesPerQuarterSecond = (0.25 / DT).toInt()

        val out = feed(stabilizer, 200) { i -> if (i < 100) 0.0 to 0.95f else 100.0 to 0.95f }

        assertEquals("must land on the new pitch", 100.0, out[100 + framesPerQuarterSecond], 2.0)
    }

    @Test
    fun `vibrato is attenuated without shifting the centre pitch`() {
        val stabilizer = PitchStabilizer()
        val centre = -7.0
        val frames = 500

        val raw = DoubleArray(frames) { centre + 30.0 * sin(2 * Math.PI * 6.0 * it * DT) }
        val out = feed(stabilizer, frames) { raw[it] to 0.95f }

        assertEquals("vibrato must not bias the reading", centre, mean(out, 200), 3.0)
        assertTrue(
            "expected vibrato amplitude ${"%.1f".format(std(out, 200))}ct below raw ${"%.1f".format(std(raw, 200))}ct",
            std(out, 200) < std(raw, 200) / 3
        )
    }

    @Test
    fun `low confidence frames move the needle less than confident ones`() {
        val confident = PitchStabilizer()
        val unsure = PitchStabilizer()

        // A sustained 30 cent drift stays below the snap threshold, so only the smoothing
        // weight decides how far the needle travels — and that weight follows confidence.
        val settled = 60
        val driftFrames = 10
        val confidentOut = feed(confident, settled + driftFrames) { i ->
            if (i < settled) 0.0 to 0.95f else 30.0 to 0.95f
        }
        val unsureOut = feed(unsure, settled + driftFrames) { i ->
            if (i < settled) 0.0 to 0.95f else 30.0 to 0.55f
        }

        val confidentTravel = confidentOut[settled + driftFrames - 1]
        val unsureTravel = unsureOut[settled + driftFrames - 1]
        assertTrue(
            "unsure frames moved ${"%.2f".format(unsureTravel)}ct vs confident ${"%.2f".format(confidentTravel)}ct",
            unsureTravel < confidentTravel * 0.6
        )
    }

    @Test
    fun `smoothing follows wall clock time rather than frame count`() {
        val fast = PitchStabilizer()
        val slow = PitchStabilizer()

        // A 20 cent drift sits below the snap threshold, so both filters must ease into it.
        val fastOut = feed(fast, 30, dt = 0.01) { 20.0 to 0.95f }
        val slowOut = feed(slow, 15, dt = 0.02) { 20.0 to 0.95f }

        assertEquals(
            "same elapsed time must give the same reading",
            fastOut[29], slowOut[14], 2.0
        )
    }

    @Test
    fun `reset returns the filter to a clean state`() {
        val stabilizer = PitchStabilizer()
        feed(stabilizer, 100) { 500.0 to 0.95f }
        stabilizer.reset()

        val out = feed(stabilizer, 1) { 0.0 to 0.95f }
        assertEquals("first frame after reset must be adopted as-is", 0.0, out[0], 0.001)
    }
}
