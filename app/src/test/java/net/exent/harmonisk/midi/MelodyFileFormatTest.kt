package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MelodyFileFormatTest {

    @Test
    fun `MIDI files are recognised by their header`() {
        assertEquals(MelodyFormat.MIDI, MelodyFileFormat.detect("MThd\u0000\u0000\u0000\u0006".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `XML documents are recognised as MusicXML`() {
        assertEquals(MelodyFormat.MUSIC_XML, MelodyFileFormat.detect("<?xml version=\"1.0\"?><score-partwise/>".toByteArray()))
        assertEquals(MelodyFormat.MUSIC_XML, MelodyFileFormat.detect("\n  <score-partwise/>".toByteArray()))
    }

    @Test
    fun `everything else is treated as ABC text`() {
        assertEquals(MelodyFormat.ABC, MelodyFileFormat.detect("X:1\nT:Tune\nK:C\nCDEF|".toByteArray()))
    }
}
