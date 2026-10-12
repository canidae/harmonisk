package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySignatureTest {

    @Test
    fun `tonic pitch classes match the circle of fifths`() {
        assertEquals(0, KeySignature.of(0, false).fifths)
        assertEquals(2, KeySignature.of(2, false).fifths)
        assertEquals(-3, KeySignature.of(3, false).fifths)
        assertEquals(5, KeySignature.of(11, false).fifths)
        assertEquals(0, KeySignature.of(9, true).fifths)
        assertEquals(-1, KeySignature.of(2, true).fifths)
        assertTrue(KeySignature.of(2, true).isMinor)
    }

    @Test
    fun `transposing moves the key with the music`() {
        assertEquals(KeySignature(2, false), KeySignature(0, false).transpose(2))
        assertEquals(KeySignature(1, false), KeySignature(-1, false).transpose(2))
        assertEquals(KeySignature(7, false), KeySignature(5, false).transpose(2))
        // A major up a minor third is C major
        assertEquals(KeySignature(0, false), KeySignature(3, false).transpose(3))
        // A minor up a minor third is C minor
        assertEquals(KeySignature(-3, true), KeySignature(0, true).transpose(3))
    }

    @Test
    fun `transposing to a flat key respells with flats`() {
        val transposed = KeySignature(0, false).transpose(5)
        assertEquals(-1, transposed.fifths)
        assertTrue(transposed.usesFlats)
    }

    @Test
    fun `key accidentals follow the sharp and flat order`() {
        assertEquals(1, KeySignature.keyAlteration(5, 1))
        assertEquals(0, KeySignature.keyAlteration(5, 0))
        assertEquals(-1, KeySignature.keyAlteration(11, -2))
        assertEquals(1, KeySignature.keyAlteration(0, 7))
        assertEquals(0, KeySignature.keyAlteration(1, 3))
    }

    @Test
    fun `track transposition carries the key`() {
        val track = MelodyTrack(
            name = "Test",
            notes = listOf(NoteEvent(60, 100, 0, 480)),
            tempoChanges = emptyList(),
            timeSignatures = emptyList(),
            ticksPerQuarterNote = 480,
            keySignature = KeySignature(0, false)
        )
        assertEquals(KeySignature(2, false), track.transpose(2).keySignature)
        assertEquals(62, track.transpose(2).notes.single().noteNumber)
    }
}
