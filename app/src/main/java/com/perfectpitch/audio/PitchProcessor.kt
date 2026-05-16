package com.perfectpitch.audio

import com.perfectpitch.music.NoteUtils
import com.perfectpitch.music.PitchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Processes audio from the microphone and emits pitch detection results.
 * Combines AudioCaptureService and PitchDetector into a single reactive pipeline.
 */
class PitchProcessor(
    private val audioCaptureService: AudioCaptureService,
    private val pitchDetector: PitchDetector,
    private val noteUtils: NoteUtils
) {
    companion object {
        const val SILENCE_THRESHOLD_DB = -50f
        /** Full analysis window size for YIN — preserves low-frequency detection. */
        const val ANALYSIS_WINDOW_SIZE = 4096
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ
    private var yinThreshold = PitchDetector.DEFAULT_THRESHOLD

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }
    fun setReferenceA4(freq: Float) { referenceA4 = freq }
    fun setYinThreshold(threshold: Float) { yinThreshold = threshold }

    /**
     * Start the pitch detection pipeline.
     *
     * Maintains a sliding window of [ANALYSIS_WINDOW_SIZE] samples. Each time a
     * new chunk arrives from AudioCaptureService (1024 samples, ~23ms), the window
     * shifts and YIN runs on the full window — giving 4× faster frame rate while
     * preserving low-frequency resolution.
     */
    fun pitchFlow(): Flow<PitchResult> {
        val slidingWindow = FloatArray(ANALYSIS_WINDOW_SIZE)
        var windowFilled = 0

        return audioCaptureService.audioFlow()
            .map { chunk ->
                // Shift window left and append new chunk
                val chunkSize = chunk.size.coerceAtMost(ANALYSIS_WINDOW_SIZE)
                if (windowFilled >= ANALYSIS_WINDOW_SIZE) {
                    // Window is full — shift left by chunkSize
                    System.arraycopy(
                        slidingWindow, chunkSize,
                        slidingWindow, 0,
                        ANALYSIS_WINDOW_SIZE - chunkSize
                    )
                    System.arraycopy(
                        chunk, 0,
                        slidingWindow, ANALYSIS_WINDOW_SIZE - chunkSize,
                        chunkSize
                    )
                } else {
                    // Still filling window — append
                    val space = ANALYSIS_WINDOW_SIZE - windowFilled
                    val toCopy = chunkSize.coerceAtMost(space)
                    System.arraycopy(chunk, 0, slidingWindow, windowFilled, toCopy)
                    windowFilled += toCopy
                }

                if (windowFilled < ANALYSIS_WINDOW_SIZE) {
                    // Not enough data yet — emit silent
                    PitchResult.SILENT
                } else {
                    processBuffer(slidingWindow)
                }
            }
            .flowOn(Dispatchers.Default)
    }

    private fun processBuffer(buffer: FloatArray): PitchResult {
        val rms = pitchDetector.rms(buffer)
        val rmsDb = pitchDetector.rmsToDb(rms)

        if (rmsDb < silenceThresholdDb) {
            return PitchResult.SILENT
        }

        val windowed = AudioUtils.applyHannWindow(buffer)

        val result = pitchDetector.detectPitch(
            buffer = windowed,
            sampleRate = AudioCaptureService.SAMPLE_RATE,
            threshold = yinThreshold
        )

        if (result.frequency <= 0f) {
            return PitchResult.SILENT
        }

        val noteInfo = noteUtils.frequencyToNote(result.frequency, referenceA4)

        return PitchResult(
            frequency = result.frequency,
            noteName = noteInfo.name,
            octave = noteInfo.octave,
            centsOff = noteInfo.centsOff,
            midiNoteNumber = noteInfo.midiNumber,
            confidence = result.confidence,
            rmsDb = rmsDb,
            isSilent = false
        )
    }
}
