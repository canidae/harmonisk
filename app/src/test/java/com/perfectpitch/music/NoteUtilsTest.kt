package com.perfectpitch.music

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2

class NoteUtilsTest {

    private lateinit var noteUtils: NoteUtils

    @Before
    fun setUp() {
        noteUtils = NoteUtils()
    }

    @Test
    fun `A4 at 440 Hz`() {
        val info = noteUtils.frequencyToNote(440f)
        assertEquals("A", info.name)
        assertEquals(4, info.octave)
        assertEquals(0f, info.centsOff, 1f)
        assertEquals(69, info.midiNumber)
    }

    @Test
    fun `C4 (middle C) at 261_63 Hz`() {
        val info = noteUtils.frequencyToNote(261.63f)
        assertEquals("C", info.name)
        assertEquals(4, info.octave)
        assertEquals(0f, info.centsOff, 2f)
        assertEquals(60, info.midiNumber)
    }

    @Test
    fun `A3 at 220 Hz`() {
        val info = noteUtils.frequencyToNote(220f)
        assertEquals("A", info.name)
        assertEquals(3, info.octave)
        assertEquals(0f, info.centsOff, 1f)
        assertEquals(57, info.midiNumber)
    }

    @Test
    fun `frequency slightly sharp reports positive cents`() {
        // 445 Hz is about 20 cents sharp of A4
        val info = noteUtils.frequencyToNote(445f)
        assertEquals("A", info.name)
        assertEquals(4, info.octave)
        assertTrue("Expected positive cents, got ${info.centsOff}", info.centsOff > 0f)
        assertTrue("Expected ~20 cents, got ${info.centsOff}", abs(info.centsOff - 19.6f) < 2f)
    }

    @Test
    fun `frequency slightly flat reports negative cents`() {
        // 435 Hz is about 20 cents flat of A4
        val info = noteUtils.frequencyToNote(435f)
        assertEquals("A", info.name)
        assertEquals(4, info.octave)
        assertTrue("Expected negative cents, got ${info.centsOff}", info.centsOff < 0f)
    }

    @Test
    fun `midiToFrequency for A4 returns 440`() {
        val freq = noteUtils.midiToFrequency(69)
        assertEquals(440f, freq, 0.01f)
    }

    @Test
    fun `midiToFrequency for C4 returns 261_63`() {
        val freq = noteUtils.midiToFrequency(60)
        assertEquals(261.63f, freq, 0.1f)
    }

    @Test
    fun `noteToFrequency for A4 returns 440`() {
        val freq = noteUtils.noteToFrequency("A", 4)
        assertEquals(440f, freq, 0.01f)
    }

    @Test
    fun `noteToFrequency for C4 returns 261_63`() {
        val freq = noteUtils.noteToFrequency("C", 4)
        assertEquals(261.63f, freq, 0.1f)
    }

    @Test
    fun `round trip frequency to note to frequency`() {
        val testFrequencies = listOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
        for (freq in testFrequencies) {
            val note = noteUtils.frequencyToNote(freq)
            val reconstructed = noteUtils.noteToFrequency(note.name, note.octave)
            val centsError = 1200.0 * log2(reconstructed.toDouble() / freq.toDouble())
            assertTrue(
                "Round trip failed for $freq Hz (${note.name}${note.octave}): cents error $centsError exceeds 0.5",
                abs(centsError) < 0.5
            )
        }
    }

    @Test
    fun `custom reference A4 at 432 Hz`() {
        val info = noteUtils.frequencyToNote(432f, refA4 = 432f)
        assertEquals("A", info.name)
        assertEquals(4, info.octave)
        assertEquals(0f, info.centsOff, 1f)
    }

    @Test
    fun `frequencyToCents calculates correctly`() {
        // One semitone up = 100 cents
        val cents = noteUtils.frequencyToCents(466.16f, 440f) // A#4 vs A4
        assertEquals(100f, cents, 2f)

        // One octave up = 1200 cents
        val octaveCents = noteUtils.frequencyToCents(880f, 440f)
        assertEquals(1200f, octaveCents, 1f)
    }

    @Test
    fun `frequencyToCents unison returns zero cents`() {
        val cents = noteUtils.frequencyToCents(440f, 440f)
        assertEquals(0f, cents, 0.01f)
    }

    @Test
    fun `frequencyToCents flat note returns negative cents`() {
        // A note below the reference should give negative cents
        val cents = noteUtils.frequencyToCents(415.30f, 440f) // G#4 vs A4 = -100 cents
        assertTrue("Expected negative cents for flat note, got $cents", cents < 0f)
        assertEquals(-100f, cents, 2f)
    }

    @Test
    fun `zero frequency returns dash note`() {
        val info = noteUtils.frequencyToNote(0f)
        assertEquals("-", info.name)
    }

    @Test
    fun `negative frequency returns dash note`() {
        val info = noteUtils.frequencyToNote(-100f)
        assertEquals("-", info.name)
    }

    @Test
    fun `midiToNoteName is correct`() {
        assertEquals("A4", noteUtils.midiToNoteName(69))
        assertEquals("C4", noteUtils.midiToNoteName(60))
        assertEquals("C0", noteUtils.midiToNoteName(12))
    }

    @Test
    fun `invalid note name returns negative frequency`() {
        val freq = noteUtils.noteToFrequency("X", 4)
        assertEquals(-1f, freq, 0.01f)
    }
}
