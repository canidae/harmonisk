package net.exent.perfectpitch.ui.melody

import android.os.SystemClock
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Listening to a song before playing it moves the play canvas through the music at the pace of the
 * audio, with no take being scored: the preview's own time is what the canvas draws.
 */
@RunWith(AndroidJUnit4::class)
class MelodyPreviewScrollDeviceTest {

    private companion object {
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"

        /** How long the synthesizer may take to open before the preview is given up on. */
        private const val PREVIEW_START_DEADLINE_MS = 15_000L

        /** How long the canvas is watched for movement once the preview has started. */
        private const val SCROLL_MS = 1_500L

        /** How far the preview must have carried the canvas in that time. */
        private const val MIN_SCROLL_SECONDS = 0.5
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
            violinAlgorithm = ViolinAlgorithm(),
            devModeState = DevModeState()
        )
    }

    @Test
    fun previewingRunsTheCanvasThroughTheSong() {
        val viewModel = viewModel()
        val melody = MelodyRepository(context, DifficultyRepository(context))
            .getBundledMelodies().first { it.id.contains(SHORTEST_MELODY) }
        assertTrue("the bundled melody must load", viewModel.loadMelody(melody))
        viewModel.enterReadyPhase()
        viewModel.initSynthesizer()

        val deadline = SystemClock.elapsedRealtime() + PREVIEW_START_DEADLINE_MS
        while (viewModel.gameState.value.previewTimeSeconds == null &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            viewModel.previewMelody()
            Thread.sleep(250)
        }
        val started = viewModel.gameState.value.previewTimeSeconds
        assertNotNull("the preview must start and drive the canvas", started)
        assertTrue(
            "the preview must count the song in before its first beat, started at %.2f s".format(started),
            started!! < 0.0
        )

        Thread.sleep(SCROLL_MS)
        val scrolled = viewModel.gameState.value.previewTimeSeconds
        assertNotNull("the preview must still be running", scrolled)
        assertTrue(
            "the canvas must scroll with the preview (%.2f s -> %.2f s)".format(started, scrolled),
            scrolled!! > started + MIN_SCROLL_SECONDS
        )

        viewModel.stopPreview()
        assertNull(
            "stopping the preview must put the canvas back on the game clock",
            viewModel.gameState.value.previewTimeSeconds
        )
        viewModel.stop()
    }
}
