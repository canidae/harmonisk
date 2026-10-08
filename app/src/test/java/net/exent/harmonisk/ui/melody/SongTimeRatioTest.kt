package net.exent.harmonisk.ui.melody

import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.midi.TempoEvent
import net.exent.harmonisk.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SongTimeRatioTest {

    private fun track(noteStartsAndEnds: List<Pair<Long, Long>>): MelodyTrack = MelodyTrack(
        name = "test",
        notes = noteStartsAndEnds.mapIndexed { i, (start, end) -> NoteEvent(60 + i, 100, start, end - start) },
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = listOf(TimeSignatureEvent(0L, 4, 4)),
        ticksPerQuarterNote = 480
    )

    // 120bpm in 4/4: 480 ticks per beat, so a 960-tick note lasts the beat and a half note lasts 1s.
    // A note of 1s from the start, a one beat rest, then a note of 2s: the music sums to 4s.
    private val song = track(listOf(0L to 960L, 1920L to 3840L))

    @Test
    fun `the song is its music from the first note to the last`() {
        assertEquals(4.0, songPlayingSeconds(song), 0.001)
    }

    @Test
    fun `a song with a rest before its first note is not longer for it`() {
        val withIntro = track(listOf(960L to 1920L, 2400L to 3360L))
        assertEquals(2.5, songPlayingSeconds(withIntro), 0.001)
        assertEquals(1.0, songTimeRatio(withIntro, waitMs = 0), 0.001)
    }

    @Test
    fun `playing the song without waiting is a ratio of one`() {
        assertEquals(1.0, songTimeRatio(song, waitMs = 0), 0.001)
    }

    @Test
    fun `time spent waiting for the player stretches the ratio`() {
        assertEquals(1.25, songTimeRatio(song, waitMs = 1000), 0.001)
        assertEquals(2.0, songTimeRatio(song, waitMs = 4000), 0.001)
    }

    @Test
    fun `a song with no notes has no ratio`() {
        assertEquals(0.0, songPlayingSeconds(track(emptyList())), 0.001)
        assertEquals(0.0, songTimeRatio(track(emptyList()), waitMs = 5000), 0.001)
    }
}
