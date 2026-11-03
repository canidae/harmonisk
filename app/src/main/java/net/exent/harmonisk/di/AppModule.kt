package net.exent.harmonisk.di

import android.content.Context
import net.exent.harmonisk.audio.AudioCaptureService
import net.exent.harmonisk.audio.AudioDeviceMonitor
import net.exent.harmonisk.audio.PitchDetector
import net.exent.harmonisk.audio.MelodyPlayer
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.data.AudioDelayRepository
import net.exent.harmonisk.data.DifficultyRepository
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.MelodySettingsRepository
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.ScoreHistoryRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.midi.MidiDeviceManager
import net.exent.harmonisk.midi.MidiInputReceiver
import net.exent.harmonisk.midi.MidiOutputSender
import net.exent.harmonisk.music.BeginnerKey
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.ScoringEngine
import net.exent.harmonisk.music.BowedAlgorithm
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
    fun providePitchDetector(
        @ApplicationContext context: Context
    ): PitchDetector = PitchDetector(context)

    @Provides
    @Singleton
    fun provideNoteUtils(): NoteUtils = NoteUtils()

    @Provides
    @Singleton
    fun providePitchProcessor(
        audioCaptureService: AudioCaptureService,
        pitchDetector: PitchDetector,
        noteUtils: NoteUtils
    ): PitchProcessor = PitchProcessor(audioCaptureService, pitchDetector, noteUtils)

    @Provides
    @Singleton
    fun provideProfileRepository(
        @ApplicationContext context: Context
    ): ProfileRepository = ProfileRepository(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        profileRepository: ProfileRepository
    ): SettingsRepository = SettingsRepository(context, profileRepository)

    @Provides
    @Singleton
    fun provideMelodyRepository(
        @ApplicationContext context: Context,
        difficultyRepository: DifficultyRepository
    ): MelodyRepository = MelodyRepository(context, difficultyRepository)

    @Provides
    @Singleton
    fun provideMelodySettingsRepository(
        @ApplicationContext context: Context,
        profileRepository: ProfileRepository
    ): MelodySettingsRepository = MelodySettingsRepository(context, profileRepository)

    @Provides
    @Singleton
    fun provideScoringEngine(): ScoringEngine = ScoringEngine()

    @Provides
    @Singleton
    fun provideBowedAlgorithm(): BowedAlgorithm = BowedAlgorithm()

    @Provides
    @Singleton
    fun provideBeginnerKey(bowedAlgorithm: BowedAlgorithm): BeginnerKey = BeginnerKey(bowedAlgorithm)

    @Provides
    @Singleton
    fun provideSoundFontSynthesizer(
        @ApplicationContext context: Context,
        audioDeviceMonitor: AudioDeviceMonitor
    ): SoundFontSynthesizer = SoundFontSynthesizer(context, audioDeviceMonitor)

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
        midiDeviceManager: MidiDeviceManager
    ): MidiInputReceiver = MidiInputReceiver(midiDeviceManager)

    @Provides
    @Singleton
    fun provideMidiOutputSender(
        midiDeviceManager: MidiDeviceManager
    ): MidiOutputSender = MidiOutputSender(midiDeviceManager)

    @Provides
    @Singleton
    fun provideScoreHistoryRepository(
        @ApplicationContext context: Context,
        profileRepository: ProfileRepository
    ): ScoreHistoryRepository = ScoreHistoryRepository(context, profileRepository)

    @Provides
    @Singleton
    fun provideDifficultyRepository(
        @ApplicationContext context: Context
    ): DifficultyRepository = DifficultyRepository(context)
}
