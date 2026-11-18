package net.exent.harmonisk.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchReferenceTest {

    @Test
    fun acceptsTheCurrentPitch() {
        assertTrue(PitchReference.matches(196f, 196f))
        assertTrue(PitchReference.matches(196.5f, 196f))
    }

    @Test
    fun rejectsHarmonicallyRelatedPitches() {
        assertFalse(PitchReference.matches(98f, 196f))
        assertFalse(PitchReference.matches(65f, 196f))
        assertFalse(PitchReference.matches(147f, 440f))
    }

    @Test
    fun rejectsWithoutAReference() {
        assertFalse(PitchReference.matches(196f, 0f))
        assertFalse(PitchReference.matches(0f, 196f))
    }
}
