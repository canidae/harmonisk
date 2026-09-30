package net.exent.perfectpitch.ui.melody

import android.content.Context
import android.os.SystemClock
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

/**
 * The melody view model with its real dependencies, for device tests that drive it directly.
 *
 * [synthesizer] is what the game checks for readiness; [melodyPlayer] is where the notes go. The
 * view model's own MIDI observer replaces the player's backend with the synthesizer on start, so a
 * test that wants to listen to the notes installs its own backend on the player afterwards.
 */
fun melodyViewModel(
    context: Context,
    synthesizer: SoundFontSynthesizer = SoundFontSynthesizer(context),
    melodyPlayer: MelodyPlayer = MelodyPlayer(synthesizer)
): MelodyViewModel {
    val noteUtils = NoteUtils()
    val deviceMonitor = AudioDeviceMonitor(context)
    val midiDeviceManager = MidiDeviceManager(context)
    return MelodyViewModel(
        pitchProcessor = PitchProcessor(AudioCaptureService(context), CrepePitchDetector(context), noteUtils),
        melodyRepository = MelodyRepository(context, DifficultyRepository(context)),
        melodySettingsRepository = MelodySettingsRepository(context),
        settingsRepository = SettingsRepository(context),
        scoringEngine = ScoringEngine(),
        noteUtils = noteUtils,
        soundFontSynthesizer = synthesizer,
        melodyPlayer = melodyPlayer,
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

/** Wait for the per-song settings to land, then put the game at [level] with [accompaniment]. */
fun MelodyViewModel.configure(level: Int, accompaniment: AccompanimentMode) {
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
