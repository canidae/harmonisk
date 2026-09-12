package net.exent.perfectpitch.ui.melody

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.exent.perfectpitch.audio.AudioCaptureService
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.CrepePitchDetector
import net.exent.perfectpitch.audio.MelodyPlayer
import net.exent.perfectpitch.audio.PitchProcessor
import net.exent.perfectpitch.audio.SoundFontSynthesizer
import net.exent.perfectpitch.data.AudioDelayRepository
import net.exent.perfectpitch.data.DifficultyRepository
import net.exent.perfectpitch.data.MelodyRepository
import net.exent.perfectpitch.data.MelodySettingsRepository
import net.exent.perfectpitch.data.ScoreHistoryRepository
import net.exent.perfectpitch.data.SettingsRepository
import net.exent.perfectpitch.midi.MidiDeviceManager
import net.exent.perfectpitch.midi.MidiInputReceiver
import net.exent.perfectpitch.midi.MidiOutputSender
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.ScoringEngine
import net.exent.perfectpitch.music.ViolinAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

/**
 * The score for song duration is min(1, songDuration * 1.05 / playTime): full marks for up to 5%
 * over the song's own duration, and points that dwindle but never reach zero however long the
 * player takes. This plays the shortest bundled melody to the end, dawdling on purpose, and checks
 * the score the game reports against the time it actually spent.
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

    private fun viewModel(): MelodyViewModel {
        val captureService = AudioCaptureService(context)
        val noteUtils = NoteUtils()
        val deviceMonitor = AudioDeviceMonitor(context)
        val midiDeviceManager = MidiDeviceManager(context)
        val synthesizer = SoundFontSynthesizer(context)
        return MelodyViewModel(
            pitchProcessor = PitchProcessor(captureService, CrepePitchDetector(context), noteUtils),
            melodyRepository = MelodyRepository(context, DifficultyRepository(context)),
            melodySettingsRepository = MelodySettingsRepository(context),
            settingsRepository = SettingsRepository(context),
            scoringEngine = ScoringEngine(),
            noteUtils = noteUtils,
            soundFontSynthesizer = synthesizer,
            melodyPlayer = MelodyPlayer(synthesizer),
            audioDelayRepository = AudioDelayRepository(context, deviceMonitor),
            audioDeviceMonitor = deviceMonitor,
            midiDeviceManager = midiDeviceManager,
            midiInputReceiver = MidiInputReceiver(context, midiDeviceManager),
            midiOutputSender = MidiOutputSender(context, midiDeviceManager),
            scoreHistoryRepository = ScoreHistoryRepository(context),
            violinAlgorithm = ViolinAlgorithm()
        )
    }

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
    fun aSlowPlayIsScoredOnHowLongTheSongTook() {
        val viewModel = viewModel()
        val melodyRepository = MelodyRepository(context, DifficultyRepository(context))
        val melody = melodyRepository.getBundledMelodies().first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(melody))
        val track = viewModel.gameState.value.melodyTrack!!
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        val player = PlayerInstrument()
        try {
            player.open()
            assertTrue("the player's instrument must open", player.isReady)

            val startWallMs = SystemClock.elapsedRealtime()
            viewModel.startPlaying()
            val leadInSeconds = -viewModel.gameState.value.currentTimeSeconds

            val state = playThrough(viewModel, track, player)
            val playSeconds = (SystemClock.elapsedRealtime() - startWallMs) / 1000.0 - leadInSeconds
            val ratio = playSeconds / track.durationSeconds
            val expected = min(1.0, ScoringEngine.TIME_GRACE_RATIO / ratio).toFloat()
            val score = state.melodyScore

            val report = (
                    "played %.1fs of a %.1fs song (%.2fx, lead-in %.1fs): phase=%s notes=%d " +
                            "timeScore=%.3f expected=%.3f overall=%.1f"
                    ).format(
                    playSeconds, track.durationSeconds, ratio, leadInSeconds, state.phase,
                    state.noteResults.size, score?.timeScore ?: -1f, expected,
                    score?.overallScore ?: -1f
                )
            Log.i(TAG, report)

            assertEquals("the song must be played to its end", PlayPhase.FINISHED, state.phase)
            assertNotNull("the finished song must report a score", score)
            assertTrue(
                "the dawdled song must run well past its own duration (ratio=%.2f)".format(ratio),
                ratio > 2.0
            )
            assertEquals(
                "song duration score must be min(1, %.2f / ratio)".format(ScoringEngine.TIME_GRACE_RATIO),
                expected, score!!.timeScore, 0.05f
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

    /** Follows the song's notes on the player's instrument, dawdling once it is under way. */
    private fun playThrough(
        viewModel: MelodyViewModel,
        track: net.exent.perfectpitch.midi.MelodyTrack,
        player: PlayerInstrument
    ): MelodyGameState {
        val deadline = SystemClock.elapsedRealtime() + PLAY_DEADLINE_MS
        var heldNote = -1
        var dawdled = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = viewModel.gameState.value
            if (state.phase == PlayPhase.FINISHED) break
            val index = state.currentNoteIndex
            val wanted = track.notes.getOrNull(index)?.noteNumber
            if (wanted != null && wanted != heldNote) {
                player.hold(wanted)
                heldNote = wanted
            }
            if (!dawdled && state.noteResults[DAWDLE_AT_INDEX] != NoteResultState.ACTIVE &&
                state.noteResults[DAWDLE_AT_INDEX] != null
            ) {
                Log.i(TAG, "dawdling for ${DAWDLE_MS / 1000}s on note $heldNote")
                Thread.sleep(DAWDLE_MS)
                dawdled = true
            }
            Thread.sleep(50)
        }
        return viewModel.gameState.value
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
