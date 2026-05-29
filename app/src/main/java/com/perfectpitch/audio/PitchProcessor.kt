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

        /**
         * Energy rise (in dB) within one chunk that triggers an onset reset.
         * On onset the sliding window is cleared so the first clean analysis frame
         * reflects only the new note — reducing octave-ambiguity during transitions.
         */
        private const val ONSET_DB_THRESHOLD = 8f
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ
    private var pitchThreshold = PitchDetector.DEFAULT_THRESHOLD

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }
    fun setReferenceA4(freq: Float) { referenceA4 = freq }
    fun setPitchThreshold(threshold: Float) { pitchThreshold = threshold }

    /**
     * Sliding-window pitch flow.
     *
     * Each incoming chunk shifts the 2048-sample analysis window. If the chunk's RMS rises
     * by ≥ ONSET_DB_THRESHOLD dB relative to the previous chunk, an onset is detected and
     * the window is reset — this prevents the old-note signal from polluting the new note's
     * first analysis frames (particularly important for octave-related intervals like F4→F5).
     */
    fun pitchFlow(): Flow<PitchResult> {
        val slidingWindow = FloatArray(ANALYSIS_WINDOW_SIZE)
        var windowFilled = 0
        var prevChunkRms = 0f

        return audioCaptureService.audioFlow()
            .map { chunk ->
                val chunkSize = chunk.size.coerceAtMost(ANALYSIS_WINDOW_SIZE)
                val chunkRms = AudioUtils.rms(chunk)
                val prevDb = AudioUtils.linearToDb(prevChunkRms)
                val currDb = AudioUtils.linearToDb(chunkRms)
                val isOnset = currDb > (silenceThresholdDb + 6f) &&
                        (currDb - prevDb) > ONSET_DB_THRESHOLD
                prevChunkRms = chunkRms

                if (isOnset) {
                    // Reset window: discard old note, start fresh with new chunk
                    slidingWindow.fill(0f)
                    System.arraycopy(chunk, 0, slidingWindow, 0, chunkSize)
                    windowFilled = chunkSize
                } else if (windowFilled >= ANALYSIS_WINDOW_SIZE) {
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

        // MPM operates on the raw signal — do NOT apply a windowing function.
        // The NSDF's per-lag energy normalization m'(τ) already handles edge effects
        // (as stated in McLeod & Wyvill 2005, Section 4).
        val result = pitchDetector.detectPitch(
            buffer = buffer,
            sampleRate = AudioCaptureService.SAMPLE_RATE,
            threshold = pitchThreshold
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

