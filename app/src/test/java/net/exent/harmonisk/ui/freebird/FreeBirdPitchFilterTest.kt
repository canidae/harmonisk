package net.exent.harmonisk.ui.freebird

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FreeBirdPitchFilterTest {

    private val dt = 33f

    private fun feed(filter: FreeBirdPitchFilter, frames: Int, target: (Int) -> Float): FloatArray {
        val out = FloatArray(frames)
        for (i in 0 until frames) out[i] = filter.accept(target(i), dt)
        return out
    }

    @Test
    fun `vibrato does not make the bird bob`() {
        val filter = FreeBirdPitchFilter()
        val centre = 0.2f
        val out = feed(filter, 400) { i ->
            centre + 0.25f * sin(2f * PI.toFloat() * 5.5f * i * dt / 1_000f)
        }
        val settled = out.copyOfRange(200, 400)
        val mean = settled.average().toFloat()
        val peak = settled.maxOf { abs(it - mean) }

        assertEquals(centre.toDouble(), mean.toDouble(), 0.02)
        assertTrue("bird bobbed ${"%.3f".format(peak)} steps", peak < 0.05f)
    }

    @Test
    fun `a new note is followed quickly`() {
        val filter = FreeBirdPitchFilter()
        val out = feed(filter, 20) { i -> if (i < 5) 0f else 0.5f }

        assertEquals(0f, out[4], 0.001f)
        assertEquals(0.5f, out[8], 0.001f)
    }

    @Test
    fun `a brief wrong note is ignored`() {
        val filter = FreeBirdPitchFilter()
        val out = feed(filter, 20) { i -> if (i in 5..6) 1f else 0f }

        assertEquals(0f, out.max(), 0.001f)
    }

    @Test
    fun `a sustained pitch offset is followed without jumping`() {
        val filter = FreeBirdPitchFilter()
        val out = feed(filter, 45) { i -> if (i < 5) 0f else 0.3f }

        assertTrue("altitude stuck at ${"%.3f".format(out[30])}", out[30] > 0.25f)
        assertTrue("altitude jumped to ${"%.3f".format(out[5])}", out[5] < 0.05f)
    }

    @Test
    fun `reset adopts the next note immediately`() {
        val filter = FreeBirdPitchFilter()
        feed(filter, 10) { 1f }
        filter.reset()

        assertEquals(0f, filter.accept(0f, dt), 0.001f)
    }
}
