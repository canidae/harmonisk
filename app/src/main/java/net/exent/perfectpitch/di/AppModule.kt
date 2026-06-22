package net.exent.perfectpitch.di

import android.content.Context
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
