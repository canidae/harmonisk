package net.exent.perfectpitch.audio

import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.PitchResult
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

class PitchProcessor(
    private val audioCaptureService: AudioCaptureService,
    private val crepeDetector: CrepePitchDetector,
    private val noteUtils: NoteUtils
) {
    companion object {
        const val SILENCE_THRESHOLD_DB = -30f

        /**
         * Analysis window size in 44100 Hz samples.
         * Must cover at least CREPE's required 64 ms window:
         *   1024 samples @ 16 kHz = 64 ms → 44100 × 0.064 ≈ 2822 samples.
         */
        const val ANALYSIS_WINDOW_SIZE = 2822

        /** Duration of the analysis window in ms — part of the input detection latency. */
        const val ANALYSIS_WINDOW_MS = (ANALYSIS_WINDOW_SIZE * 1000 + AudioConstants.SAMPLE_RATE / 2) / AudioConstants.SAMPLE_RATE

        /**
         * Energy rise (in dB) within one chunk that triggers an onset reset.
         * On onset the sliding window is cleared so the very first CREPE inference
         * reflects only the new note's signal — reduces transition ambiguity.
         * The onset condition must persist for ONSET_DEBOUNCE_CHUNKS consecutive
         * chunks to filter single-chunk amplitude modulation blips. 5 dB with a
         * debounce of 2 beats both 3 dB (constant false resets, window starvation)
         * and 8 dB (misses gradual onsets) on real instrument recordings.
         */
        private const val ONSET_DB_THRESHOLD = 5f
        private const val ONSET_DEBOUNCE_CHUNKS = 2

        /**
         * Onset detection delay in ms. A fresh note needs this many consecutive
         * chunks of rising energy before the sliding window resets, adding to the
         * input detection latency for real instruments.
         */
        const val INPUT_ONSET_MS = (ONSET_DEBOUNCE_CHUNKS * AudioCaptureService.BUFFER_SIZE_SAMPLES * 1000 + AudioConstants.SAMPLE_RATE / 2) / AudioConstants.SAMPLE_RATE

        /**
         * After this many milliseconds of consecutive silence, the prior-note frequency
         * hint is cleared. Prevents a note before a long rest from biasing the next note.
         */
        private const val HINT_RESET_MS = 800L
        private const val HINT_RESET_NANOS = HINT_RESET_MS * 1_000_000L
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_A4_FREQ

    /** Duration of the most recent CREPE inference, used for input-delay calibration. */
    @Volatile
    var inferenceLatencyMs: Int = 0
        private set

    /** Frequency of the most recently detected pitch, passed to CREPE as an octave-hint. */
    private var lastFrequency = 0f
    private var lastDetectedNano = 0L

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }
    fun setReferenceA4(freq: Float) { referenceA4 = freq }

    /**
     * Sliding-window pitch flow using CREPE for inference.
     *
     * Each incoming chunk (512 samples at 44100 Hz = ~11.6 ms) shifts the 2822-sample
     * analysis window. On energy onset the window is reset so CREPE receives a clean frame
     * for the new note as quickly as possible.
     *
     * @param source Raw 44100 Hz chunks. Defaults to the microphone; tests inject recorded audio
     *               to drive this exact framing with repeatable input.
     */
    fun pitchFlow(source: Flow<FloatArray> = audioCaptureService.audioFlow()): Flow<PitchResult> {
        val slidingWindow = FloatArray(ANALYSIS_WINDOW_SIZE)
        var windowFilled = 0
        var prevChunkRms = 0f
        var onsetStreak = 0

        return source
            .map { chunk ->
                val chunkSize = chunk.size.coerceAtMost(ANALYSIS_WINDOW_SIZE)
                val chunkRms = AudioUtils.rms(chunk)
                val prevDb = AudioUtils.linearToDb(prevChunkRms)
                val currDb = AudioUtils.linearToDb(chunkRms)
                val qualifies = currDb > (silenceThresholdDb + 6f) &&
                        abs(currDb - prevDb) > ONSET_DB_THRESHOLD
                prevChunkRms = chunkRms
                onsetStreak = if (qualifies) onsetStreak + 1 else 0
                val isOnset = onsetStreak >= ONSET_DEBOUNCE_CHUNKS

                if (isOnset) {
                    onsetStreak = 0
                    slidingWindow.fill(0f)
                    System.arraycopy(chunk, 0, slidingWindow, 0, chunkSize)
                    windowFilled = chunkSize
                    lastFrequency = 0f
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
        if (rmsDb < silenceThresholdDb) return handleSilent()

        val start = System.nanoTime()
        val result = crepeDetector.detectPitch(buffer = buffer, hintFreq = lastFrequency)
        val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
        if (elapsed in 0..500) inferenceLatencyMs = elapsed
        if (result.frequency <= 0f) return handleSilent()

        lastFrequency = result.frequency
        lastDetectedNano = System.nanoTime()

        val noteInfo = noteUtils.frequencyToNote(result.frequency, referenceA4)
        return PitchResult(
            frequency = result.frequency,
            noteName = noteInfo.name,
            octave = noteInfo.octave,
            centsOff = noteInfo.centsOff,
            midiNoteNumber = noteInfo.midiNumber,
            isSilent = false,
            confidence = result.confidence
        )
    }

    private fun handleSilent(): PitchResult {
        if (System.nanoTime() - lastDetectedNano > HINT_RESET_NANOS) lastFrequency = 0f
        return PitchResult.SILENT
    }
}
