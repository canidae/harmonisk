@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package net.exent.harmonisk.midi

import alphaTab.Settings
import alphaTab.importer.AlphaTexImporter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The alphaTex we generate is fed straight to alphaTab, so the key signature we write has to
 * be one alphaTab understands and has to land on the first bar. alphaTab stores a minor key
 * as its relative major plus a minor key-signature type.
 */
class AlphaTexKeySignatureTest {

    private fun firstBar(tex: String) = AlphaTexImporter().let { importer ->
        importer.initFromString(tex, Settings())
        importer.readScore().tracks[0].staves[0].bars[0]
    }

    private fun track(notes: List<NoteEvent>, key: KeySignature) = MelodyTrack(
        name = "Test",
        notes = notes,
        tempoChanges = listOf(TempoEvent(0, 500_000)),
        timeSignatures = listOf(TimeSignatureEvent(0, 4, 4)),
        ticksPerQuarterNote = 480,
        keySignature = key
    )

    private fun playedNotes(tex: String): List<Int> {
        val beats = firstBar(tex).voices[0].beats
        return (0 until beats.length.toInt())
            .filter { beats[it].notes.length > 0 }
            .map { beats[it].notes[0].realValue.toInt() }
    }

    @Test
    fun `a major key signature reaches the first bar`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(NoteEvent(62, 100, 0, 1920)), KeySignature(2, false))
        )
        val bar = firstBar(tex)
        assertEquals(alphaTab.model.KeySignature.D, bar.keySignature)
        assertEquals(alphaTab.model.KeySignatureType.Major, bar.keySignatureType)
    }

    @Test
    fun `a flat key signature reaches the first bar`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(NoteEvent(63, 100, 0, 1920)), KeySignature(-3, false))
        )
        assertEquals(alphaTab.model.KeySignature.Eb, firstBar(tex).keySignature)
    }

    @Test
    fun `a minor key signature reaches the first bar as its relative major`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(NoteEvent(76, 100, 0, 1920)), KeySignature(1, true))
        )
        val bar = firstBar(tex)
        assertEquals(alphaTab.model.KeySignature.G, bar.keySignature)
        assertEquals(alphaTab.model.KeySignatureType.Minor, bar.keySignatureType)
    }

    @Test
    fun `flat spelled notes keep their pitch`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(NoteEvent(70, 100, 0, 480), NoteEvent(63, 100, 480, 480)),
                KeySignature(-3, false)
            )
        )
        assertEquals(listOf(70, 63), playedNotes(tex))
    }
}
