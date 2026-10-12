package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiToAlphaTexTest {

    private val ppq = 480

    private fun track(
        notes: List<NoteEvent>,
        tempos: List<TempoEvent> = listOf(TempoEvent(0, 500_000)),
        timeSignatures: List<TimeSignatureEvent> = listOf(TimeSignatureEvent(0, 4, 4)),
        name: String = "Test",
        keySignature: KeySignature? = null
    ) = MelodyTrack(
        name = name,
        notes = notes,
        tempoChanges = tempos,
        timeSignatures = timeSignatures,
        ticksPerQuarterNote = ppq,
        keySignature = keySignature
    )

    private fun note(midi: Int, startTick: Long, durationTicks: Long) = NoteEvent(midi, 100, startTick, durationTicks)

    @Test
    fun `note names use scientific pitch notation`() {
        assertEquals("C4", MidiToAlphaTex.noteName(60))
        assertEquals("C#4", MidiToAlphaTex.noteName(61))
        assertEquals("A4", MidiToAlphaTex.noteName(69))
        assertEquals("B3", MidiToAlphaTex.noteName(59))
        assertEquals("C-1", MidiToAlphaTex.noteName(0))
        assertEquals("G9", MidiToAlphaTex.noteName(127))
    }

    @Test
    fun `quarter notes fill a 4-4 bar`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 480),
                    note(62, 480, 480),
                    note(64, 960, 480),
                    note(65, 1440, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 D4.4 E4.4 F4.4 |", tex)
    }

    @Test
    fun `gate time durations are quantized to the grid`() {
        // MIDI files start notes on the grid but often end them ~5% early (gate time)
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 455),
                    note(62, 480, 431),
                    note(64, 960, 911)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 D4.4 E4.2 |", tex)
    }

    @Test
    fun `eighth and dotted durations`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 240),   // eighth
                    note(62, 240, 240), // eighth
                    note(64, 480, 720)  // dotted quarter
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.8 D4.8 E4.4 E4{t}.8 r.4{d} |", tex)
    }

    @Test
    fun `note crossing bar line is tied`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(note(60, 0, 2880)))
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.1 | C4{t}.2 r.2 |", tex)
    }

    @Test
    fun `long note decomposes into tied pieces within bar`() {
        // Starts on the second sixteenth of the bar, lasts a quarter
        val tex = MidiToAlphaTex.generate(
            track(listOf(note(60, 120, 480)))
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 r.16 C4.16 C4{t}.8 C4{t}.16 r.2 r.8{d} |", tex)
    }

    @Test
    fun `overlapping notes are rendered as chords`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 960),
                    note(62, 600, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 C4{t}.16 (C4{t} D4).16 (C4{t} D4{t}).8 D4{t}.16 r.4{dd} |", tex)
    }

    @Test
    fun `simultaneous notes form a chord`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 480),
                    note(64, 0, 480),
                    note(67, 0, 480),
                    note(62, 480, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 (C4 E4 G4).4 D4.4 r.2 |", tex)
    }

    @Test
    fun `tempo change at bar start`() {
        val tex = MidiToAlphaTex.generate(
            track(
                notes = listOf(note(60, 0, 1920), note(60, 1920, 1920)),
                tempos = listOf(TempoEvent(0, 500_000), TempoEvent(1920, 400_000))
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.1 | \\tempo 150 C4.1 |", tex)
    }

    @Test
    fun `mid bar tempo change becomes a beat effect at the change position`() {
        val tex = MidiToAlphaTex.generate(
            track(
                notes = listOf(note(60, 0, 1920)),
                tempos = listOf(TempoEvent(0, 500_000), TempoEvent(960, 400_000))
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.2 C4{t}.2{tempo 150} |", tex)
    }

    @Test
    fun `time signature change starts a new bar`() {
        val tex = MidiToAlphaTex.generate(
            track(
                notes = listOf(note(60, 0, 1920), note(60, 1920, 1440)),
                timeSignatures = listOf(TimeSignatureEvent(0, 4, 4), TimeSignatureEvent(1920, 3, 4))
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.1 | \\ts 3 4 C4.2{d} |", tex)
    }

    @Test
    fun `low melodies use bass clef`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(note(48, 0, 480)))
        )
        assertTrue(tex.startsWith("\\tempo 120 \\instrument piano . \\ts 4 4 \\clef bass"))
        assertTrue(tex.contains("C3.4"))
    }

    @Test
    fun `empty track renders a bar of rest`() {
        val tex = MidiToAlphaTex.generate(track(emptyList()))
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 r.1 |", tex)
    }

    @Test
    fun `gap between notes becomes rests`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 480),
                    note(62, 960, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 r.4 D4.4 r.4 |", tex)
    }

    @Test
    fun `truncated quarter notes read as plain quarters`() {
        // MuseScore exports a quarter note as 470 ticks with a 10 tick gap, so quantizing its
        // end lands one grid step early and the note used to become a dotted note tied to a
        // sixteenth. Only the last note still carries the shortened duration.
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 10, 470),
                    note(62, 490, 470),
                    note(64, 970, 470)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 D4.4 E4.4 r.4 |", tex)
    }

    @Test
    fun `truncated sixteenth notes read as plain sixteenths`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 5, 115),
                    note(62, 125, 115),
                    note(64, 245, 115),
                    note(65, 365, 115)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.16 D4.16 E4.16 F4.16 r.2{d} |", tex)
    }

    @Test
    fun `a gap of a grid step is left as a rest`() {
        // A rest that can be written must not be filled in, even at the merge limit.
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 480),
                    note(62, 600, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 r.16 D4.16 D4{t}.8 D4{t}.16 r.4{dd} |", tex)
    }

    @Test
    fun `a clipped half note is not written as a double dot tied to a sliver`() {
        // godnatt has a note of 959 ticks in 3/4: it must not come out as D4.4{dd} D4{t}.16.
        val tex = MidiToAlphaTex.generate(
            track(
                notes = listOf(
                    note(69, 0, 479),
                    note(62, 480, 959)
                ),
                timeSignatures = listOf(TimeSignatureEvent(0, 3, 4))
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 3 4 A4.4 D4.4 D4{t}.4 |", tex)
    }

    @Test
    fun `a note that stops well short is not extended`() {
        // A staccato quarter leaves an audible gap, far larger than any gate time.
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 240),
                    note(62, 480, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.8 r.8 D4.4 r.2 |", tex)
    }

    @Test
    fun `the key signature is written into the score`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(note(62, 0, 1920)), keySignature = KeySignature(2, false))
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 \\ks d D4.1 |", tex)
    }

    @Test
    fun `a minor key signature carries the minor suffix`() {
        val tex = MidiToAlphaTex.generate(
            track(listOf(note(69, 0, 1920)), keySignature = KeySignature(0, true))
        )
        assertTrue(tex.contains("\\ks aminor"))
    }

    @Test
    fun `flat keys spell notes with flats`() {
        val tex = MidiToAlphaTex.generate(
            track(
                notes = listOf(note(63, 0, 480), note(70, 480, 480)),
                keySignature = KeySignature(-3, false)
            )
        )
        assertTrue(tex.contains("Eb4"))
        assertTrue(tex.contains("Bb4"))
        assertTrue(tex.contains("\\ks eb"))
    }

    @Test
    fun `transposing a track moves its key signature`() {
        val transposed = track(
            listOf(note(60, 0, 1920)),
            keySignature = KeySignature(0, false)
        ).transpose(2)
        val tex = MidiToAlphaTex.generate(transposed)
        assertTrue(tex.contains("\\ks d"))
        assertTrue(tex.contains("D4.1"))
    }
}
