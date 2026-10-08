package net.exent.harmonisk.di

import android.content.Context
import net.exent.harmonisk.audio.AudioCaptureService
import net.exent.harmonisk.audio.AudioDeviceMonitor
import net.exent.harmonisk.audio.CrepePitchDetector
import net.exent.harmonisk.audio.MelodyPlayer
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.data.AudioDelayRepository
import net.exent.harmonisk.data.DifficultyRepository
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.MelodySettingsRepository
import net.exent.harmonisk.data.ScoreHistoryRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.midi.MidiDeviceManager
import net.exent.harmonisk.midi.MidiInputReceiver
import net.exent.harmonisk.midi.MidiOutputSender
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.ScoringEngine
import net.exent.harmonisk.music.ViolinAlgorithm
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioCaptureService(
        @ApplicationContext context: Context
    ): AudioCaptureService = AudioCaptureService(context)

    @Provides
    @Singleton
    fun provideCrepePitchDetector(
        @ApplicationContext context: Context
    ): CrepePitchDetector = CrepePitchDetector(context)

    @Provides
    @Singleton
    fun provideNoteUtils(): NoteUtils = NoteUtils()

    @Provides
    @Singleton
    fun providePitchProcessor(
        audioCaptureService: AudioCaptureService,
        crepeDetector: CrepePitchDetector,
        noteUtils: NoteUtils
    ): PitchProcessor = PitchProcessor(audioCaptureService, crepeDetector, noteUtils)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository = SettingsRepository(context)

    @Provides
    @Singleton
    fun provideMelodyRepository(
        @ApplicationContext context: Context,
        difficultyRepository: DifficultyRepository
    ): MelodyRepository = MelodyRepository(context, difficultyRepository)

    @Provides
    @Singleton
    fun provideMelodySettingsRepository(
        @ApplicationContext context: Context
    ): MelodySettingsRepository = MelodySettingsRepository(context)

    @Provides
    @Singleton
    fun provideScoringEngine(): ScoringEngine = ScoringEngine()

    @Provides
    @Singleton
    fun provideViolinAlgorithm(): ViolinAlgorithm = ViolinAlgorithm()

    @Provides
    @Singleton
    fun provideSoundFontSynthesizer(
        @ApplicationContext context: Context
    ): SoundFontSynthesizer = SoundFontSynthesizer(context)

    @Provides
    @Singleton
    fun provideAudioDeviceMonitor(
        @ApplicationContext context: Context
    ): AudioDeviceMonitor = AudioDeviceMonitor(context)

    @Provides
    @Singleton
    fun provideAudioDelayRepository(
        @ApplicationContext context: Context,
        audioDeviceMonitor: AudioDeviceMonitor
    ): AudioDelayRepository = AudioDelayRepository(context, audioDeviceMonitor)

    @Provides
    @Singleton
    fun provideMelodyPlayer(
        synthesizer: SoundFontSynthesizer
    ): MelodyPlayer = MelodyPlayer(synthesizer)

    @Provides
    @Singleton
    fun provideMidiDeviceManager(
        @ApplicationContext context: Context
    ): MidiDeviceManager = MidiDeviceManager(context)

    @Provides
    @Singleton
    fun provideMidiInputReceiver(
        @ApplicationContext context: Context,
        midiDeviceManager: MidiDeviceManager
    ): MidiInputReceiver = MidiInputReceiver(context, midiDeviceManager)

    @Provides
    @Singleton
    fun provideMidiOutputSender(
        @ApplicationContext context: Context,
        midiDeviceManager: MidiDeviceManager
    ): MidiOutputSender = MidiOutputSender(context, midiDeviceManager)

    @Provides
    @Singleton
    fun provideScoreHistoryRepository(
        @ApplicationContext context: Context
    ): ScoreHistoryRepository = ScoreHistoryRepository(context)

    @Provides
    @Singleton
    fun provideDifficultyRepository(
        @ApplicationContext context: Context
    ): DifficultyRepository = DifficultyRepository(context)
}
