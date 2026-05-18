package com.perfectpitch.audio

import com.perfectpitch.music.NoteUtils
import com.perfectpitch.music.PitchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

class PitchProcessor(
    private val audioCaptureService: AudioCaptureService,
    private val pitchDetector: PitchDetector,
    private val noteUtils: NoteUtils
) {
    companion object {
        const val SILENCE_THRESHOLD_DB = -50f
        const val ANALYSIS_WINDOW_SIZE = 2048
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ
    private var yinThreshold = PitchDetector.DEFAULT_THRESHOLD

    private val windowedBuffer = FloatArray(ANALYSIS_WINDOW_SIZE)

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }
    fun setReferenceA4(freq: Float) { referenceA4 = freq }
    fun setYinThreshold(threshold: Float) { yinThreshold = threshold }

    /**
     * Sliding-window pitch flow: each new 1024-sample chunk shifts a 2048-sample window
     * so YIN runs at ~23ms frames while preserving low-frequency resolution.
     */
    fun pitchFlow(): Flow<PitchResult> {
        val slidingWindow = FloatArray(ANALYSIS_WINDOW_SIZE)
        var windowFilled = 0

        return audioCaptureService.audioFlow()
            .map { chunk ->
                val chunkSize = chunk.size.coerceAtMost(ANALYSIS_WINDOW_SIZE)
                if (windowFilled >= ANALYSIS_WINDOW_SIZE) {
                    System.arraycopy(slidingWindow, chunkSize, slidingWindow, 0, ANALYSIS_WINDOW_SIZE - chunkSize)
                    System.arraycopy(chunk, 0, slidingWindow, ANALYSIS_WINDOW_SIZE - chunkSize, chunkSize)
                } else {
                    val toCopy = chunkSize.coerceAtMost(ANALYSIS_WINDOW_SIZE - windowFilled)
                    System.arraycopy(chunk, 0, slidingWindow, windowFilled, toCopy)
                    windowFilled += toCopy
                }
                if (windowFilled < ANALYSIS_WINDOW_SIZE) PitchResult.SILENT
                else processBuffer(slidingWindow)
            }
            .flowOn(Dispatchers.Default)
    }

    private fun processBuffer(buffer: FloatArray): PitchResult {
        val rmsDb = AudioUtils.linearToDb(AudioUtils.rms(buffer))
        if (rmsDb < silenceThresholdDb) return PitchResult.SILENT

        AudioUtils.applyHannWindow(buffer, windowedBuffer)
        val result = pitchDetector.detectPitch(
            buffer = windowedBuffer,
            sampleRate = AudioCaptureService.SAMPLE_RATE,
            threshold = yinThreshold
        )
        if (result.frequency <= 0f) return PitchResult.SILENT

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
