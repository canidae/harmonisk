package net.exent.perfectpitch.ui.melody

import android.content.Context
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

/** The melody view model with its real dependencies, for device tests that drive it directly. */
fun melodyViewModel(context: Context): MelodyViewModel {
    val noteUtils = NoteUtils()
    val deviceMonitor = AudioDeviceMonitor(context)
    val midiDeviceManager = MidiDeviceManager(context)
    val synthesizer = SoundFontSynthesizer(context)
    return MelodyViewModel(
        pitchProcessor = PitchProcessor(AudioCaptureService(context), CrepePitchDetector(context), noteUtils),
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
