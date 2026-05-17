package com.perfectpitch.di

import android.content.Context
import com.perfectpitch.audio.AudioCaptureService
import com.perfectpitch.audio.AudioDeviceMonitor
import com.perfectpitch.audio.MelodyPlayer
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.audio.PitchDetector
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.AudioDelayProfileRepository
import com.perfectpitch.data.AudioInputDelayRepository
import com.perfectpitch.data.MelodyRepository
import com.perfectpitch.data.MelodySettingsRepository
import com.perfectpitch.data.SettingsRepository
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
    fun providePitchDetector(): PitchDetector = PitchDetector()

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
    fun provideAudioDelayProfileRepository(
        @ApplicationContext context: Context,
        audioDeviceMonitor: AudioDeviceMonitor
    ): AudioDelayProfileRepository = AudioDelayProfileRepository(context, audioDeviceMonitor)

    @Provides
    @Singleton
    fun provideAudioInputDelayRepository(
        @ApplicationContext context: Context,
        audioDeviceMonitor: AudioDeviceMonitor,
        audioCaptureService: AudioCaptureService
    ): AudioInputDelayRepository = AudioInputDelayRepository(context, audioDeviceMonitor, audioCaptureService)

    @Provides
    @Singleton
    fun provideMelodyPlayer(
        synthesizer: MelodySynthesizer
    ): MelodyPlayer = MelodyPlayer(synthesizer)
}
