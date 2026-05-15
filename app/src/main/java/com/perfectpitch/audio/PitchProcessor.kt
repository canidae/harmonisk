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
        const val MEDIAN_WINDOW_SIZE = 5
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ
    private var yinThreshold = PitchDetector.DEFAULT_THRESHOLD

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }
    fun setReferenceA4(freq: Float) { referenceA4 = freq }
    fun setYinThreshold(threshold: Float) { yinThreshold = threshold }

    /**
     * Start the pitch detection pipeline.
     * Emits PitchResult for each audio frame processed.
     */
    fun pitchFlow(): Flow<PitchResult> {
        val recentFrequencies = ArrayDeque<Float>(MEDIAN_WINDOW_SIZE)

        return audioCaptureService.audioFlow()
            .map { buffer -> processBuffer(buffer, recentFrequencies) }
            .flowOn(Dispatchers.Default)
    }

    private fun processBuffer(
        buffer: FloatArray,
        recentFrequencies: ArrayDeque<Float>
    ): PitchResult {
        val rms = pitchDetector.rms(buffer)
        val rmsDb = pitchDetector.rmsToDb(rms)

        // Check for silence
        if (rmsDb < silenceThresholdDb) {
            recentFrequencies.clear()
            return PitchResult.SILENT
        }

        // Detect pitch
        val rawFrequency = pitchDetector.detectPitch(
            buffer = buffer,
            sampleRate = AudioCaptureService.SAMPLE_RATE,
            threshold = yinThreshold
        )

        if (rawFrequency <= 0f) {
            return PitchResult.SILENT
        }

        // Moving median filter to reduce jitter
        recentFrequencies.addLast(rawFrequency)
        if (recentFrequencies.size > MEDIAN_WINDOW_SIZE) {
            recentFrequencies.removeFirst()
        }
        val smoothedFrequency = median(recentFrequencies.toList())

        // Convert to note
        val noteInfo = noteUtils.frequencyToNote(smoothedFrequency, referenceA4)

        return PitchResult(
            frequency = smoothedFrequency,
            noteName = noteInfo.name,
            octave = noteInfo.octave,
            centsOff = noteInfo.centsOff,
            midiNoteNumber = noteInfo.midiNumber,
            confidence = 1f - (pitchDetector.detectPitch(buffer, threshold = 1f).let { 0f }), // simplified
            rmsDb = rmsDb,
            isSilent = false
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }
}
