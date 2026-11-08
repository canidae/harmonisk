package net.exent.harmonisk.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.midi.TempoEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodyPlayerTest {

    private class RecordingNotePlayer : NotePlayer {
        val events = mutableListOf<String>()

        override val isReady: Boolean get() = true

        @Synchronized
        override fun noteOn(channel: Int, note: Int, velocity: Int) {
            events.add("on $channel $note")
        }

        @Synchronized
        override fun noteOff(channel: Int, note: Int) {
            events.add("off $channel $note")
        }

        @Synchronized
        override fun allNotesOff() {
            events.add("all")
        }

        @Synchronized
        fun snapshot(): List<String> = events.toList()
    }

    private fun track(channel: Int = 0, noteNumber: Int = 60, durationTicks: Long = 4_000L) = MelodyTrack(
        name = "test",
        notes = listOf(NoteEvent(noteNumber, 100, startTick = 0L, durationTicks = durationTicks)),
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = emptyList(),
        ticksPerQuarterNote = 480,
        channel = channel
    )

    @Test
    fun stoppingGameSyncReleasesOnlyItsOwnNotes() {
        val player = RecordingNotePlayer()
        val melodyPlayer = MelodyPlayer(player)

        melodyPlayer.updateGameTime(0.1, listOf(track(channel = 3, noteNumber = 62)))
        melodyPlayer.stop()

        assertEquals(listOf("on 3 62", "off 3 62"), player.snapshot())
    }

    @Test
    fun stoppingPreviewReleasesOnlyItsOwnNotes() = runBlocking {
        val player = RecordingNotePlayer()
        val melodyPlayer = MelodyPlayer(player)
        val scope = CoroutineScope(Dispatchers.Default)

        melodyPlayer.playWithTiming(listOf(track(channel = 1, noteNumber = 55, durationTicks = 48_000L)), scope)
        withTimeout(2_000) {
            while (player.snapshot().none { it.startsWith("on ") }) Thread.sleep(10)
        }
        melodyPlayer.stop()
        withTimeout(2_000) {
            while (player.snapshot().none { it.startsWith("off ") }) Thread.sleep(10)
        }
        scope.cancel()

        assertTrue(player.snapshot().contains("off 1 55"))
        assertTrue(player.snapshot().none { it == "all" })
    }
}
