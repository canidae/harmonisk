package net.exent.harmonisk.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchDebouncerTest {

    @Test
    fun holdsBriefPitchChanges() {
        val debouncer = PitchDebouncer(requiredFrames = 3)
        assertEquals(196f, debouncer.filter(196f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(430f, debouncer.filter(430f), 0.01f)
    }

    @Test
    fun ignoresAThreeFrameOctaveSpike() {
        val debouncer = PitchDebouncer()
        assertEquals(196f, debouncer.filter(196f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(430f, debouncer.filter(430f), 0.01f)
    }

    @Test
    fun acceptsANewNoteAfterThreeFrames() {
        val debouncer = PitchDebouncer(requiredFrames = 3)
        debouncer.filter(196f)
        assertEquals(196f, debouncer.filter(220f), 0.01f)
        assertEquals(196f, debouncer.filter(220f), 0.01f)
        assertEquals(220f, debouncer.filter(220f), 0.01f)
    }

    @Test
    fun keepsTheStablePitchAcrossBriefSilence() {
        val debouncer = PitchDebouncer()
        debouncer.filter(196f)
        repeat(6) { debouncer.filter(0f) }
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(196f, debouncer.filter(430f), 0.01f)
        assertEquals(430f, debouncer.filter(430f), 0.01f)
    }

    @Test
    fun acceptsTheFirstPitchAfterReset() {
        val debouncer = PitchDebouncer(requiredFrames = 3)
        debouncer.filter(196f)
        debouncer.reset()
        assertEquals(430f, debouncer.filter(430f), 0.01f)
    }
}
