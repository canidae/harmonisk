package net.exent.perfectpitch.ui.melody

import android.Manifest
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.exent.perfectpitch.audio.AudioCaptureService
import net.exent.perfectpitch.audio.AudioConstants
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
import kotlin.math.pow

/**
 * "Jeg Kan Spille Fiolin" opens with two D4 notes in a row, and how the game has to treat them
 * depends on who is making the sound.
 *
 * With the app playing the melody — the audio output a player turns on — the microphone hears the
 * app's own notes, and there is nothing in them to tell from a player: measured through the phone's
 * own speaker, a released violin note is still sounding at its full level 300 ms later, so the 26 ms
 * the notation leaves between the two notes is not audible at all and the song would stop dead on the
 * repeat. The game therefore credits the notes as it plays them.
 *
 * With the app silent, the only thing on the microphone is the player, and one tone held through both
 * notes is one note: the repeated pitch counts only once the player has broken the tone, which is what
 * makes the game worth playing.
 *
 * The audio here is the app's own soundfont throughout: the melody through the app's own player, and
 * the player's part through the same instrument on its own output. Nothing is measured with a tone
 * laid over the top of it, and the media volume is left at half of maximum, where the speaker is not
 * squashing the level differences a pause is made of.
 */
@RunWith(AndroidJUnit4::class)
class MelodyRepeatedNoteDeviceTest {

    companion object {
        private const val TAG = "MelodyRepeatedNote"
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"
        private const val HELD_NOTE = 62
        private const val VIOLIN_PROGRAM = 40
        private const val PLAYER_VELOCITY = 100
        private const val PAST_FIRST_REPEAT_SECONDS = 1.2
        private const val PLAYER_FIXTURE_SECONDS = 10.0

        /** A level where the song stops and waits for the player. */
        private const val WAIT_LEVEL = 1

        /** A level where the song runs on rather than waiting for the player. */
        private const val CONTINUOUS_LEVEL = 4

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
            "the melody must open with two repeated notes",
            listOf(HELD_NOTE, HELD_NOTE), track.notes.take(2).map { it.noteNumber }
        )
    }

    /**
     * The app playing the melody with nobody playing along: what a player hears when they turn the
     * audio output on, and what the game has to be able to score. It must not stop on the repeat.
     */
    @Test
    fun theAppPlayingTheMelodyScoresTheRepeatedNote() {
        val viewModel = viewModel()
        loadMelody(viewModel)
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.ALL)
        viewModel.initSynthesizer()
        Thread.sleep(3000)

        try {
            val state = playUntilPastTheRepeat(viewModel)
            Log.i(
                TAG,
                "app playing the melody alone at level $WAIT_LEVEL: phase=%s time=%.2fs results=%s".format(
                    state.phase, state.currentTimeSeconds, state.noteResults
                )
            )
            assertTrue(
                "the app's own repeated note must be scored, not left waiting " +
                        "(results=${state.noteResults}, phase=${state.phase})",
                state.noteResults[1] in CREDITED
            )
            assertTrue(
                "the song must move past the opening notes (time=${state.currentTimeSeconds})",
                state.currentTimeSeconds > PAST_FIRST_REPEAT_SECONDS || state.phase == PlayPhase.FINISHED
            )
        } finally {
            viewModel.stop()
        }
    }

    /** The same, at a level where the song runs on instead of waiting for the player. */
    @Test
    fun theAppPlayingTheMelodyScoresTheRepeatedNoteInContinuousMode() {
        val viewModel = viewModel()
        loadMelody(viewModel)
        viewModel.configure(CONTINUOUS_LEVEL, AccompanimentMode.ALL)
        viewModel.initSynthesizer()
        Thread.sleep(3000)

        try {
            val state = playUntilPastTheRepeat(viewModel)
            Log.i(
                TAG,
                "app playing the melody alone at level $CONTINUOUS_LEVEL: phase=%s time=%.2fs results=%s".format(
                    state.phase, state.currentTimeSeconds, state.noteResults
                )
            )
            assertTrue(
                "the app's own repeated note must be scored (results=${state.noteResults})",
                state.noteResults[1] in CREDITED
            )
        } finally {
            viewModel.stop()
        }
    }

    /**
     * With the app silent, a player holding one D4 through both notes has played one note, so the game
     * must credit only the first of them.
     */
    @Test
    fun aHeldNoteIsCreditedForOneOfTwoRepeatedNotes() {
        val viewModel = viewModel()
        loadMelody(viewModel)
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        val player = PlayerInstrument()
        try {
            player.open()
            assertTrue("the player's instrument must open", player.isReady)
            player.hold(HELD_NOTE)

            val state = playUntilPastTheRepeat(viewModel)
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
                "the held D4 must not be credited for the second, repeated note " +
                        "(results=${state.noteResults})",
                state.noteResults[1] !in CREDITED
            )
        } finally {
            viewModel.stop()
            player.close()
        }
    }

    /**
     * The same opening with the player articulating the repeat — the note played, released and taken up
     * again. The fixture is a plain tone rather than the soundfont because the bundled violin goes on
     * sounding at full level for 300 ms after it is released, so it cannot articulate a note this short.
     */
    @Test
    fun anArticulatedRepeatIsCreditedWhenTheAppIsSilent() {
        val viewModel = viewModel()
        loadMelody(viewModel)
        viewModel.configure(WAIT_LEVEL, AccompanimentMode.NONE)

        val playback: AudioTrack = TunerAudioFixture.play(context, articulatedNotes(PLAYER_FIXTURE_SECONDS))
        try {
            val state = playUntilPastTheRepeat(viewModel)
            Log.i(
                TAG,
                "articulated repeat with the app silent: phase=%s time=%.2fs results=%s".format(
                    state.phase, state.currentTimeSeconds, state.noteResults
                )
            )
            assertTrue(
                "a repeated note the player articulates must be credited (results=${state.noteResults})",
                state.noteResults[1] in CREDITED
            )
        } finally {
            viewModel.stop()
            playback.stop()
            playback.release()
        }
    }

    /**
     * Puts the game into a known level and accompaniment, after the melody's saved settings have
     * landed — they are read asynchronously and would otherwise overwrite what the test asks for.
     */
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

    /** Runs the game until it is past the repeated note, or has sat on it for a while. */
    private fun playUntilPastTheRepeat(viewModel: MelodyViewModel): MelodyGameState {
        viewModel.startPlaying()
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var waitingAtRepeatSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = viewModel.gameState.value
            if (state.currentTimeSeconds > PAST_FIRST_REPEAT_SECONDS || state.phase == PlayPhase.FINISHED) break
            if (state.phase == PlayPhase.WAITING_FOR_NOTE && state.currentNoteIndex == 1) {
                if (waitingAtRepeatSince == 0L) waitingAtRepeatSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - waitingAtRepeatSince > 6000) break
            } else {
                waitingAtRepeatSince = 0L
            }
            Thread.sleep(50)
        }
        return viewModel.gameState.value
    }

    /**
     * A D4 played the way a player repeats a note: [onSeconds] of tone, [pauseSeconds] of nothing, over
     * and over. The release is a fast ramp in dB, so the note ends the way a bow or a breath does
     * rather than with a click.
     */
    private fun articulatedNotes(seconds: Double, onSeconds: Double = 0.45, pauseSeconds: Double = 0.15): FloatArray {
        val tone = TunerAudioFixture.tone(NoteUtils.midiToFrequency(HELD_NOTE, 440f).toDouble(), seconds)
        val rate = AudioConstants.SAMPLE_RATE
        val onSamples = (onSeconds * rate).toInt()
        val pauseSamples = (pauseSeconds * rate).toInt()
        val release = (0.04 * rate).toInt()
        val attack = (0.03 * rate).toInt()
        for (i in tone.indices) {
            val phase = i % (onSamples + pauseSamples)
            val gain = when {
                phase < attack -> phase.toDouble() / attack
                phase >= onSamples -> {
                    val into = phase - onSamples
                    if (into < release) 10.0.pow(-60.0 * into / release / 20.0) else 0.0
                }
                else -> 1.0
            }
            tone[i] = (tone[i] * gain).toFloat()
        }
        return tone
    }
}
