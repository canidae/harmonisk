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
import net.exent.perfectpitch.data.DevModeState
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Jeg Kan Spille Fiolin" opens with two D4 notes in a row. A note is credited from the pitch the
 * game hears, and the game does not ask to hear the tone stop between two notes of the same pitch, so
 * one D4 held through both notes is given both of them.
 *
 * The player here is the app's own soundfont on its own output, so nothing is laid over the app's own
 * playing, and the media volume is left at half of maximum.
 */
@RunWith(AndroidJUnit4::class)
class MelodyRepeatedNoteDeviceTest {

    private companion object {
        private const val TAG = "MelodyRepeatedNote"
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"
        private const val HELD_NOTE = 62
        private const val VIOLIN_PROGRAM = 40
        private const val PLAYER_VELOCITY = 100

        /** Index of the repeated note the melody opens with, and of the note after it. */
        private const val REPEATED_NOTE_INDEX = 1
        private const val NEXT_NOTE_INDEX = 2

        /** The pitch the melody changes to after the repeat. */
        private const val NEXT_NOTE = 69

        /** A level where the song stops and waits for the player. */
        private const val WAIT_LEVEL = 1

        private val CREDITED = setOf(
            NoteResultState.PERFECT,
            NoteResultState.GREAT,
            NoteResultState.GOOD,
            NoteResultState.OK
        )
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The same objects Hilt builds for the running app, so the game behaves here exactly as it does
     * when a player is looking at it.
     */
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
            violinAlgorithm = ViolinAlgorithm(),
            devModeState = DevModeState()
        )
    }

    /**
     * The player's instrument on its own output, so the game's own playing is untouched: the app's
     * soundfont, which is the closest thing to a violin a test can produce on its own.
     */
    private inner class PlayerInstrument : AutoCloseable {
        private val synthesizer = SoundFontSynthesizer(context)

        fun open() {
            synthesizer.open()
            synthesizer.setInstrument(0, VIOLIN_PROGRAM)
        }

        val isReady: Boolean get() = synthesizer.isReady

        fun hold(note: Int) = synthesizer.noteOn(0, note, PLAYER_VELOCITY)

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

    private fun loadMelody(viewModel: MelodyViewModel) {
        val melodyRepository = MelodyRepository(context, DifficultyRepository(context))
        val melody = melodyRepository.getBundledMelodies().first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(melody))
        val track = viewModel.gameState.value.melodyTrack!!
        Log.i(
            TAG,
            "melody: " + track.notes.take(3).joinToString(" ") {
                "%d@%.2f-%.2f".format(
                    it.noteNumber, track.noteStartSeconds(it),
                    track.noteStartSeconds(it) + track.noteDurationSeconds(it)
                )
            }
        )
        assertEquals(
            "the melody must open with two repeated notes and then change pitch",
            listOf(HELD_NOTE, HELD_NOTE, NEXT_NOTE), track.notes.take(3).map { it.noteNumber }
        )
    }

    /** One D4 held from before the melody starts and never released, with the app silent. */
    @Test
    fun aHeldNoteIsCreditedForBothRepeatedNotes() {
        val viewModel = viewModel()
        loadMelody(viewModel)
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        val player = PlayerInstrument()
        try {
            player.open()
            assertTrue("the player's instrument must open", player.isReady)
            player.hold(HELD_NOTE)

            val state = playPastTheRepeat(viewModel)
            Log.i(
                TAG,
                "one D4 held on the player's violin: phase=%s time=%.2fs results=%s".format(
                    state.phase, state.currentTimeSeconds, state.noteResults
                )
            )
            assertTrue(
                "the held D4 must be credited for the first note (was it heard at all?)",
                state.noteResults[0] in CREDITED
            )
            assertTrue(
                "the same tone held on must be credited for the second, repeated note " +
                        "(results=${state.noteResults})",
                state.noteResults[REPEATED_NOTE_INDEX] in CREDITED
            )
            assertEquals(
                "the song must move past the repeat to the note that changes pitch " +
                        "(time=${state.currentTimeSeconds}, results=${state.noteResults})",
                NEXT_NOTE_INDEX, state.currentNoteIndex
            )
            assertTrue(
                "the note that changes pitch must not be credited from the held D4 " +
                        "(results=${state.noteResults}, time=${state.currentTimeSeconds})",
                state.noteResults[NEXT_NOTE_INDEX] == NoteResultState.ACTIVE
            )
        } finally {
            viewModel.stop()
            player.close()
        }
    }

    /**
     * Puts the game into a known level and accompaniment, after the melody's saved settings have
     * landed — they are read asynchronously and would otherwise overwrite what the test asks for.
     */

    /**
     * Runs the game until the repeat is behind it — the song stops again at the note where the pitch
     * changes — or until it has sat on the repeat for a while, which is what the caller asserts against.
     */
    private fun playPastTheRepeat(viewModel: MelodyViewModel): MelodyGameState {
        viewModel.startPlaying()
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var waitingAtRepeatSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = viewModel.gameState.value
            if (state.phase == PlayPhase.WAITING_FOR_NOTE && state.currentNoteIndex > REPEATED_NOTE_INDEX) break
            if (state.phase == PlayPhase.FINISHED) break
            if (state.phase == PlayPhase.WAITING_FOR_NOTE && state.currentNoteIndex == REPEATED_NOTE_INDEX) {
                if (waitingAtRepeatSince == 0L) waitingAtRepeatSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - waitingAtRepeatSince > 6000) break
            } else {
                waitingAtRepeatSince = 0L
            }
            Thread.sleep(50)
        }
        return viewModel.gameState.value
    }
}
