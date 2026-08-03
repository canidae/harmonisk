package net.exent.perfectpitch.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiToAlphaTexTest {

    private val ppq = 480

    private fun track(
        notes: List<NoteEvent>,
        tempos: List<TempoEvent> = listOf(TempoEvent(0, 500_000)),
        timeSignatures: List<TimeSignatureEvent> = listOf(TimeSignatureEvent(0, 4, 4)),
        name: String = "Test"
    ) = MelodyTrack(
        name = name,
        notes = notes,
        tempoChanges = tempos,
        timeSignatures = timeSignatures,
        ticksPerQuarterNote = ppq
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
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.8 D4.8 E4.4{d} r.4{d} |", tex)
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
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 r.16 C4.16 C4{t}.8{d} r.2 r.8{d} |", tex)
    }

    @Test
    fun `overlapping notes after quantization are clamped`() {
        val tex = MidiToAlphaTex.generate(
            track(
                listOf(
                    note(60, 0, 960),
                    note(62, 600, 480)
                )
            )
        )
        assertEquals("\\tempo 120 \\instrument piano . \\ts 4 4 C4.4 C4{t}.16 D4.16 D4{t}.8{d} r.4{dd} |", tex)
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
}
