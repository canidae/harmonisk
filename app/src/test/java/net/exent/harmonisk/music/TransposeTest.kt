package net.exent.harmonisk.music

import org.junit.Assert.assertEquals
import org.junit.Test

class TransposeTest {

    @Test
    fun semitonesSplitIntoOctaveAndKey() {
        assertEquals(Transpose(-1, 2), Transpose.fromSemitones(-10))
        assertEquals(Transpose(-1, 0), Transpose.fromSemitones(-12))
        assertEquals(Transpose(0, -5), Transpose.fromSemitones(-5))
        assertEquals(Transpose(1, -5), Transpose.fromSemitones(7))
        assertEquals(Transpose(0, 6), Transpose.fromSemitones(6))
        assertEquals(Transpose(0, -6), Transpose.fromSemitones(-6))
        assertEquals(Transpose(-1, -6), Transpose.fromSemitones(-18))
        assertEquals(Transpose(2, 6), Transpose.fromSemitones(30))
        assertEquals(Transpose(-2, -6), Transpose.fromSemitones(-30))
    }

    @Test
    fun semitonesRoundTrip() {
        for (semitones in -Transpose.MAX_SEMITONES..Transpose.MAX_SEMITONES) {
            assertEquals(semitones, Transpose.fromSemitones(semitones).semitones)
        }
    }

    @Test
    fun decompositionStaysWithinSliderRange() {
        val values = listOf(-100, -31, -30, -7, 0, 7, 30, 31, 100).map(Transpose::fromSemitones)
        for (transpose in values) {
            assertEquals(transpose.octave.coerceIn(Transpose.MIN_OCTAVE, Transpose.MAX_OCTAVE), transpose.octave)
            assertEquals(transpose.key.coerceIn(Transpose.MIN_KEY, Transpose.MAX_KEY), transpose.key)
        }
    }
}
