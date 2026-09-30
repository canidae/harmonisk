package net.exent.perfectpitch.ui.melody

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.exent.perfectpitch.audio.SoundFontSynthesizer
import net.exent.perfectpitch.data.DifficultyRepository
import net.exent.perfectpitch.data.MelodyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Song duration is scored on the time the player took against the music they had to play: the song's
 * own length, from its first note to the end of its last, plus whatever the song sat waiting for them.
 * The two bars counted in before the first note, the slack the song lingers for after its last note
 * and the app's own lag are not the player's time, so a song played at its own pace scores full marks
 * on its duration and only dawdling costs points.
 *
 * The player here is the app's own soundfont on its own output, so nothing is laid over the app's
 * own playing, and the media volume is left at half of maximum.
 */
@RunWith(AndroidJUnit4::class)
class MelodySongDurationScoreDeviceTest {

    private companion object {
        private const val TAG = "MelodySongDuration"
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"
        private const val VIOLIN_PROGRAM = 40
        private const val PLAYER_VELOCITY = 100
        private const val WAIT_LEVEL = 1

        /** Time spent sitting on a note without moving on, to overrun the song's duration. */
        private const val DAWDLE_MS = 20_000L

        /** The note index to dawdle at, once the melody is under way. */
        private const val DAWDLE_AT_INDEX = 2

        private const val PLAY_DEADLINE_MS = 90_000L
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private inner class PlayerInstrument : AutoCloseable {
        private val synthesizer = SoundFontSynthesizer(context)

        fun open() {
            synthesizer.open()
            synthesizer.setInstrument(0, VIOLIN_PROGRAM)
        }

        val isReady: Boolean get() = synthesizer.isReady

        fun hold(note: Int) {
            synthesizer.allNotesOff()
            synthesizer.noteOn(0, note, PLAYER_VELOCITY)
        }

        override fun close() {
            synthesizer.allNotesOff()
            synthesizer.close()
        }
    }

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @Test
    fun aSongPlayedAtItsOwnPaceScoresFullMarksOnDuration() {
        val viewModel = melodyViewModel(context)
        val melody = MelodyRepository(context, DifficultyRepository(context))
            .getBundledMelodies().first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(melody))
        val track = viewModel.gameState.value.melodyTrack!!
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        val player = PlayerInstrument()
        try {
            player.open()
            assertTrue("the player's instrument must open", player.isReady)

            val state = playThrough(viewModel, track, player, dawdleAt = null)
            val score = state.melodyScore

            assertEquals("the song must be played to its end", PlayPhase.FINISHED, state.phase)
            assertNotNull("the finished song must report a score", score)
            assertEquals(
                "a song played at the pace of the song itself must score full marks on its duration",
                1f, score!!.timeScore, 0.001f
            )
        } finally {
            viewModel.stop()
            player.close()
        }
    }

    @Test
    fun aDawdledSongIsScoredOnHowLongItTook() {
        val viewModel = melodyViewModel(context)
        val melody = MelodyRepository(context, DifficultyRepository(context))
            .getBundledMelodies().first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(melody))
        val track = viewModel.gameState.value.melodyTrack!!
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        val player = PlayerInstrument()
        try {
            player.open()
            assertTrue("the player's instrument must open", player.isReady)

            val state = playThrough(viewModel, track, player, dawdleAt = DAWDLE_AT_INDEX)
            val score = state.melodyScore

            assertEquals("the song must be played to its end", PlayPhase.FINISHED, state.phase)
            assertNotNull("the finished song must report a score", score)
            assertTrue(
                "dawdling for %.0fs on a %.1fs song must cost the duration score (%.3f)".format(
                    DAWDLE_MS / 1000.0, track.durationSeconds, score!!.timeScore
                ),
                score.timeScore < 0.5f
            )
            assertTrue(
                "however long the song took, the score must stay above zero (%.3f)".format(score.timeScore),
                score.timeScore > 0f
            )
        } finally {
            viewModel.stop()
            player.close()
        }
    }

    /** Follows the song's notes on the player's instrument, dawdling once it is under way if asked. */
    private fun playThrough(
        viewModel: MelodyViewModel,
        track: net.exent.perfectpitch.midi.MelodyTrack,
        player: PlayerInstrument,
        dawdleAt: Int?
    ): MelodyGameState {
        val startWallMs = SystemClock.elapsedRealtime()
        viewModel.startPlaying()
        val leadInSeconds = -viewModel.gameState.value.currentTimeSeconds
        val deadline = SystemClock.elapsedRealtime() + PLAY_DEADLINE_MS
        var heldNote = -1
        var dawdled = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = viewModel.gameState.value
            if (state.phase == PlayPhase.FINISHED) break
            val wanted = track.notes.getOrNull(state.currentNoteIndex)?.noteNumber
            if (wanted != null && wanted != heldNote) {
                player.hold(wanted)
                heldNote = wanted
            }
            if (!dawdled && dawdleAt != null && state.noteResults[dawdleAt] != null &&
                state.noteResults[dawdleAt] != NoteResultState.ACTIVE
            ) {
                Log.i(TAG, "dawdling for ${DAWDLE_MS / 1000}s on note $heldNote")
                Thread.sleep(DAWDLE_MS)
                dawdled = true
            }
            Thread.sleep(20)
        }
        val state = viewModel.gameState.value
        val playSeconds = (SystemClock.elapsedRealtime() - startWallMs) / 1000.0 - leadInSeconds
        Log.i(
            TAG,
            ("played %.1fs of a %.1fs song (%.2fx, lead-in %.1fs, dawdled=%s): phase=%s notes=%d " +
                    "misses=%d timeScore=%.3f overall=%.1f").format(
                playSeconds, track.durationSeconds, playSeconds / track.durationSeconds,
                leadInSeconds, dawdled, state.phase, state.noteResults.size,
                state.noteResults.count { it.value == NoteResultState.MISS },
                state.melodyScore?.timeScore ?: -1f, state.melodyScore?.overallScore ?: -1f
            )
        )
        return state
    }

    private fun MelodyViewModel.configure(level: Int, accompaniment: AccompanimentMode) {
        Thread.sleep(3000)
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            setAccompanimentMode(accompaniment)
            setLevel(level)
            Thread.sleep(100)
            val state = gameState.value
            if (state.currentLevel == level && state.accompanimentMode == accompaniment) return
        } while (SystemClock.elapsedRealtime() < deadline)
        val state = gameState.value
        throw AssertionError(
            "the game must run at level $level with $accompaniment, not level ${state.currentLevel} " +
                    "/ ${state.accompanimentMode}"
        )
    }
}
