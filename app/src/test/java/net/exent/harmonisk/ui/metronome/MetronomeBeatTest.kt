package net.exent.harmonisk.ui.metronome

import org.junit.Assert.assertEquals
import org.junit.Test

class MetronomeBeatTest {

    @Test
    fun `a beat cycles normal accented silent normal`() {
        var beats = cycleBeatState(1, emptySet(), emptySet())
        assertEquals(setOf(1), beats.first)
        assertEquals(emptySet<Int>(), beats.second)

        beats = cycleBeatState(1, beats.first, beats.second)
        assertEquals(emptySet<Int>(), beats.first)
        assertEquals(setOf(1), beats.second)

        beats = cycleBeatState(1, beats.first, beats.second)
        assertEquals(emptySet<Int>(), beats.first)
        assertEquals(emptySet<Int>(), beats.second)
    }

    @Test
    fun `beats cycle independently`() {
        var beats = cycleBeatState(1, emptySet(), emptySet())
        beats = cycleBeatState(2, beats.first, beats.second)
        beats = cycleBeatState(2, beats.first, beats.second)
        beats = cycleBeatState(3, beats.first, beats.second)

        assertEquals(setOf(1, 3), beats.first)
        assertEquals(setOf(2), beats.second)
    }
}
