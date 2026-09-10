package net.exent.perfectpitch.ui.melody

import android.Manifest
import android.media.AudioTrack
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
import net.exent.perfectpitch.audio.TunerAudioFixture
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Jeg Kan Spille Fiolin" opens with two D4 notes in a row. A player who holds one continuous D4
 * through both has played one note, so the game must credit only the first of them.
 *
 * This runs the real game: the melody is loaded from the app's own assets, and the pitch comes from
 * the real CREPE detector listening to the microphone while the device plays the tone out of its own
 * speaker — the nearest thing to a player holding a note that a test can produce on its own.
 */
@RunWith(AndroidJUnit4::class)
class MelodyRepeatedNoteDeviceTest {

    companion object {
        private const val TAG = "MelodyRepeatedNote"
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"
        private const val HELD_NOTE = 62
        private const val TONE_SECONDS = 16.0
        private const val PAST_FIRST_REPEAT_SECONDS = 1.2
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
            violinAlgorithm = ViolinAlgorithm()
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

    @Test
    fun aHeldToneIsCreditedForOneOfTwoRepeatedNotes() {
        val viewModel = viewModel()
        val melodyRepository = MelodyRepository(context, DifficultyRepository(context))

        val melody = melodyRepository.getBundledMelodies()
            .first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(melody))

        // Let the saved per-melody settings land before overriding what the app plays.
        Thread.sleep(1000)
        viewModel.setAccompanimentMode(AccompanimentMode.NONE)

        val track = viewModel.gameState.value.melodyTrack!!
        val opening = track.notes.take(3).map {
            "%d@%.2f-%.2f".format(
                it.noteNumber,
                track.noteStartSeconds(it),
                track.noteStartSeconds(it) + track.noteDurationSeconds(it)
            )
        }
        Log.i(TAG, "melody notes at the start: ${opening.joinToString(" ")}")
        assertEquals("the melody must open with two repeated notes", listOf(HELD_NOTE, HELD_NOTE), track.notes.take(2).map { it.noteNumber })

        val tone = TunerAudioFixture.tone(NoteUtils.midiToFrequency(HELD_NOTE, 440f).toDouble(), TONE_SECONDS)
        val playback: AudioTrack = TunerAudioFixture.play(context, tone)
        try {
            viewModel.startPlaying()

            // Wait until the game is past the repeated note, or has stopped dead on it.
            val deadline = SystemClock.elapsedRealtime() + 25_000
            var waitingAtRepeatSince = 0L
            var stuckOnRepeat = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = viewModel.gameState.value
                if (state.currentTimeSeconds > PAST_FIRST_REPEAT_SECONDS || state.phase == PlayPhase.FINISHED) break
                if (state.phase == PlayPhase.WAITING_FOR_NOTE && state.currentNoteIndex == 1) {
                    if (waitingAtRepeatSince == 0L) waitingAtRepeatSince = SystemClock.elapsedRealtime()
                    else if (SystemClock.elapsedRealtime() - waitingAtRepeatSince > 2500) {
                        stuckOnRepeat = true
                        break
                    }
                } else {
                    waitingAtRepeatSince = 0L
                }
                Thread.sleep(50)
            }

            val state = viewModel.gameState.value
            Log.i(
                TAG,
                "held one D4 through the opening: phase=%s time=%.2fs waitMode=%b metronome=%b stuckOnRepeat=%b results=%s".format(
                    state.phase, state.currentTimeSeconds, state.waitMode, state.metronomeEnabled,
                    stuckOnRepeat, state.noteResults
                )
            )

            assertEquals(
                "the held D4 must be credited for the first note (was the tone heard at all?)",
                true, state.noteResults[0] in CREDITED
            )
            assertTrue(
                "the held D4 must not be credited for the second, repeated note (results=${state.noteResults})",
                state.noteResults[1] !in CREDITED
            )
        } finally {
            viewModel.stop()
            playback.stop()
            playback.release()
        }
    }
}
