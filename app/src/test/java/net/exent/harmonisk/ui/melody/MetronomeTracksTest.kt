package net.exent.harmonisk.ui.melody

import net.exent.harmonisk.data.MetronomeMode
import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.midi.TempoEvent
import net.exent.harmonisk.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeTracksTest {

    private val metronome = MelodyTrack(
        name = "metronome",
        notes = listOf(
            NoteEvent(86, 120, -960L, 48L),
            NoteEvent(85, 100, -480L, 48L)
        ),
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = listOf(TimeSignatureEvent(0L, 4, 4)),
        ticksPerQuarterNote = 480,
        channel = 9
    )

    private val countIn = listOf(
        MelodyTrack(
            name = "count-in",
            notes = metronome.notes.filter { it.startTick < 0 },
            tempoChanges = metronome.tempoChanges,
            timeSignatures = metronome.timeSignatures,
            ticksPerQuarterNote = metronome.ticksPerQuarterNote,
            channel = 9
        )
    )

    private fun tracks(mode: MetronomeMode, waitMode: Boolean, track: MelodyTrack? = metronome) =
        metronomeTracks(mode, waitMode, track, countIn)

    @Test
    fun switchingTheMetronomeOffSilencesIt() {
        assertTrue(tracks(MetronomeMode.OFF, waitMode = false).isEmpty())
        assertTrue(tracks(MetronomeMode.OFF, waitMode = true).isEmpty())
    }

    @Test
    fun leadInStopsBeforeTheSongOnEveryLevel() {
        assertEquals(countIn, tracks(MetronomeMode.LEAD_IN, waitMode = false))
        assertEquals(countIn, tracks(MetronomeMode.LEAD_IN, waitMode = true))
    }

    @Test
    fun onKeepsClickingThroughTheSongWhenThePlayerKeepsUp() {
        assertEquals(listOf(metronome), tracks(MetronomeMode.ON, waitMode = false))
    }

    @Test
    fun onFallsBackToTheCountInWhileTheSongWaitsForThePlayer() {
        assertEquals(countIn, tracks(MetronomeMode.ON, waitMode = true))
    }

    @Test
    fun aSongWithoutAMetronomePlaysNone() {
        for (mode in MetronomeMode.entries) {
            assertTrue(tracks(mode, waitMode = false, track = null).isEmpty())
        }
    }
}
