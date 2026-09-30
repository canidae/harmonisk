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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a waiting song comes to rest.
 *
 * The bars run ahead of the game clock by the input delay so that a note played as a bar reaches the
 * now line is scored on that note once the microphone and the detector have caught up. A song that
 * stops for the player must not do that: as the clock stops on the note it is waiting for, the note
 * has to sit on the now line, where the player can see it and play it, and stay there while the note
 * is missed.
 */
@RunWith(AndroidJUnit4::class)
class MelodyWaitModeRestDeviceTest {

    companion object {
        private const val TAG = "MelodyWaitRest"
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"

        /** A level where the song stops and waits for the player. */
        private const val WAIT_LEVEL = 1

        /** How much of the note the bars may still show past the now line when the song stops. */
        private const val REST_TOLERANCE_SECONDS = 0.02

        /** How long a missed note is left alone before checking the song has not crept on. */
        private const val MISSED_NOTE_SECONDS = 700L

        /** How long the song is given to settle after it stops before its resting place is read. */
        private const val SETTLE_SECONDS = 0.4

        private const val CREEP_TOLERANCE_SECONDS = 0.005
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The same objects Hilt builds for the running app, so the game behaves here as it does on screen. */
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
    }

    /**
     * The app silent and nobody playing, so the song stops on the first note and is left there: the
     * note the player is asked to play has to be resting on the now line.
     */
    @Test
    fun aWaitingSongRestsOnTheNoteItWaitsFor() {
        val viewModel = viewModel()
        loadMelody(viewModel)
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        try {
            viewModel.startPlaying()
            awaitWaiting(viewModel)
            // Anything the song does after stopping (let the sound that is already in flight catch up)
            // happens before the resting place is read.
            Thread.sleep((SETTLE_SECONDS * 1000).toLong())
            val resting = viewModel.gameState.value
            assertEquals(
                "the song must still be waiting for the player after settling (phase=${resting.phase})",
                PlayPhase.WAITING_FOR_NOTE, resting.phase
            )
            val track = resting.melodyTrack
            val awaited = track?.notes?.getOrNull(resting.currentNoteIndex)
            assertTrue(
                "the song must be waiting on a note, not between notes " +
                        "(phase=${resting.phase}, note=${resting.currentNoteIndex})",
                awaited != null
            )
            val noteStart = track!!.noteStartSeconds(awaited!!)
            val inputDelayMs = inputDelayMs(viewModel)
            val lead = nowLineLeadSeconds(resting.waitMode, resting.phase, inputDelayMs)

            Log.i(
                TAG,
                (
                        "at rest: phase=%s clock=%.3fs, waiting on note %d at %.3fs, bars lead the clock " +
                                "by %.0fms -> %.0fms past the now line"
                        ).format(
                        resting.phase, resting.currentTimeSeconds, awaited.noteNumber, noteStart,
                        lead * 1000, (resting.currentTimeSeconds + lead - noteStart) * 1000
                    )
            )

            assertEquals(
                "the song must stop on the note it is waiting for",
                noteStart, resting.currentTimeSeconds, REST_TOLERANCE_SECONDS
            )
            assertEquals(
                "in wait mode the bars must not run ahead of the game clock",
                0.0, lead, 1e-9
            )

            val stoppedAt = resting.currentTimeSeconds
            Thread.sleep(MISSED_NOTE_SECONDS)
            val stillWaiting = viewModel.gameState.value
            Log.i(
                TAG,
                "%.0fms later with nobody playing: phase=%s clock=%.3fs note=%d".format(
                    MISSED_NOTE_SECONDS.toDouble(), stillWaiting.phase,
                    stillWaiting.currentTimeSeconds, stillWaiting.currentNoteIndex
                )
            )
            assertEquals(
                "a missed note must leave the song where it stopped",
                PlayPhase.WAITING_FOR_NOTE, stillWaiting.phase
            )
            assertEquals(
                "a missed note must not creep on past the now line",
                stoppedAt, stillWaiting.currentTimeSeconds, CREEP_TOLERANCE_SECONDS
            )
        } finally {
            viewModel.stop()
        }
    }

    /** Runs the song until it stops for the player, or fails if it does not stop at all. */
    private fun awaitWaiting(viewModel: MelodyViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (viewModel.gameState.value.phase == PlayPhase.WAITING_FOR_NOTE) return
            Thread.sleep(20)
        }
        throw AssertionError("the song must stop and wait for the player (phase=${viewModel.gameState.value.phase})")
    }

    /** The input delay the game is using, read the way the screen reads it: as a subscriber. */
    private fun inputDelayMs(viewModel: MelodyViewModel): Int =
        runBlocking { withTimeoutOrNull(2000) { viewModel.activeInputDelay.drop(1).first() } } ?: 0

    /**
     * Puts the game into a known level and accompaniment, after the melody's saved settings have
     * landed — they are read asynchronously and would otherwise overwrite what the test asks for.
     */

}
