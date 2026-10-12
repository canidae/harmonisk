package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyDetectorTest {

    private fun melody(vararg midi: Int): List<NoteEvent> =
        midi.mapIndexed { index, note -> NoteEvent(note, 100, index * 480L, 480) }

    @Test
    fun `a C major scale is detected as C major`() {
        val key = KeyDetector.detect(melody(60, 62, 64, 65, 67, 69, 71, 72))
        assertEquals(KeySignature(0, false), key)
    }

    @Test
    fun `a D major scale is detected as D major`() {
        val key = KeyDetector.detect(melody(62, 64, 66, 67, 69, 71, 73, 74))
        assertEquals(KeySignature(2, false), key)
    }

    @Test
    fun `a G major melody is detected as G major`() {
        val key = KeyDetector.detect(melody(67, 69, 71, 72, 74, 76, 78, 79))
        assertEquals(KeySignature(1, false), key)
    }

    @Test
    fun `a melody that leans on A is detected as A minor`() {
        val key = KeyDetector.detect(
            melody(69, 69, 72, 69, 76, 74, 72, 71, 69, 69)
        )
        assertEquals(KeySignature(0, true), key)
    }

    @Test
    fun `too few notes cannot name a key`() {
        assertNull(KeyDetector.detect(melody(60, 62, 64)))
        assertNull(KeyDetector.detect(emptyList()))
    }
}
