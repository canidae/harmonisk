package com.perfectpitch.di

import android.content.Context
import com.perfectpitch.audio.AudioCaptureService
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.CrepePitchDetector
import com.perfectpitch.audio.MelodyPlayer
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.AudioDelayRepository
import com.perfectpitch.data.MelodyRepository
import com.perfectpitch.data.MelodySettingsRepository
import com.perfectpitch.data.ScoreHistoryRepository
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.midi.MidiDeviceManager
import com.perfectpitch.midi.MidiInputReceiver
import com.perfectpitch.midi.MidiOutputSender
import com.perfectpitch.music.NoteUtils
import com.perfectpitch.music.ScoringEngine
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
        @ApplicationContext context: Context
    ): MelodyRepository = MelodyRepository(context)

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
    fun provideMelodySynthesizer(): MelodySynthesizer = MelodySynthesizer()

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
        synthesizer: MelodySynthesizer
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
}
