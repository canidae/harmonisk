package net.exent.harmonisk.audio

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

private data class PitchWindow(val samples: FloatArray, val sounding: Boolean)

class PitchProcessor(
    private val audioCaptureService: AudioCaptureService,
    private val pitchDetector: PitchDetector,
    private val noteUtils: NoteUtils
) {
    companion object {
        const val SILENCE_THRESHOLD_DB = AudioConstants.DEFAULT_SILENCE_THRESHOLD_DB

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
         * On onset the sliding window is cleared so the very first pitch detection
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
        const val INPUT_ONSET_MS = (ONSET_DEBOUNCE_CHUNKS * AudioConstants.BUFFER_SIZE_SAMPLES * 1000 + AudioConstants.SAMPLE_RATE / 2) / AudioConstants.SAMPLE_RATE

        private const val RELEASE_PEAK_RATIO = 0.3f
        private const val RELEASE_DEBOUNCE_CHUNKS = 4

        /**
         * How far below the instrument's lowest open string a reading may sit and still count.
         * The lowest string is the one a player is most likely to leave a little flat, and its
         * note sits exactly on the range floor, so a few cents of tuning error or detector jitter
         * would otherwise throw every frame away.
         */
        private const val LOWEST_NOTE_MARGIN_CENTS = 100f

        internal fun lowestDetectableFrequency(instrument: Instrument, referenceA4: Float): Float =
            NoteUtils.midiToFrequency(instrument.tuning.first(), referenceA4) *
                2.0.pow(-LOWEST_NOTE_MARGIN_CENTS / 1200.0).toFloat()
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_CONCERT_PITCH
    private var instrument = Instrument.VIOLIN
    @Volatile
    private var lowestFrequency = NoteUtils.midiToFrequency(instrument.tuning.first(), referenceA4)

    /** Duration of the most recent pitch detection, used for input-delay calibration. */
    @Volatile
    var inferenceLatencyMs: Int = 0
        private set

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }

    fun setReferenceA4(frequency: Float) {
        referenceA4 = frequency
        updateLowestFrequency()
    }

    fun setInstrument(instrument: Instrument) {
        this.instrument = instrument
        updateLowestFrequency()
    }

    private fun updateLowestFrequency() {
        lowestFrequency = lowestDetectableFrequency(instrument, referenceA4)
    }

    /**
     * Sliding-window pitch flow using the pitch detector.
     *
     * Each incoming chunk (512 samples at 44100 Hz = ~11.6 ms) shifts the 2822-sample
     * analysis window. On energy onset the window is reset so the detector receives a clean
     * frame for the new note as quickly as possible.
     *
     * @param source Raw 44100 Hz chunks. Defaults to the microphone; tests inject recorded audio
     *               to drive this exact framing with repeatable input.
     */
    fun pitchFlow(source: Flow<FloatArray> = audioCaptureService.audioFlow()): Flow<PitchResult> {
        val slidingWindow = FloatArray(ANALYSIS_WINDOW_SIZE)
        var windowFilled = 0
        var prevChunkRms = 0f
        var onsetStreak = 0
        var releaseStreak = 0
        var peakRms = 0f

        return source
            .mapNotNull { chunk ->
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
                    releaseStreak = 0
                    peakRms = chunkRms
                    slidingWindow.fill(0f)
                    System.arraycopy(chunk, 0, slidingWindow, 0, chunkSize)
                    windowFilled = chunkSize
                } else {
                    if (chunkRms > peakRms) peakRms = chunkRms
                    val releasing = peakRms > 0f && chunkRms < peakRms * RELEASE_PEAK_RATIO
                    releaseStreak = if (releasing) releaseStreak + 1 else 0
                    if (windowFilled >= ANALYSIS_WINDOW_SIZE) {
                        System.arraycopy(slidingWindow, chunkSize, slidingWindow, 0, ANALYSIS_WINDOW_SIZE - chunkSize)
                        System.arraycopy(chunk, 0, slidingWindow, ANALYSIS_WINDOW_SIZE - chunkSize, chunkSize)
                    } else {
                        val toCopy = chunkSize.coerceAtMost(ANALYSIS_WINDOW_SIZE - windowFilled)
                        System.arraycopy(chunk, 0, slidingWindow, windowFilled, toCopy)
                        windowFilled += toCopy
                    }
                }
                if (windowFilled < ANALYSIS_WINDOW_SIZE) null
                else PitchWindow(slidingWindow.copyOf(), releaseStreak < RELEASE_DEBOUNCE_CHUNKS)
            }
            .buffer(Channel.CONFLATED)
            .map { window -> processBuffer(window.samples).copy(sounding = window.sounding) }
            .flowOn(Dispatchers.Default)
    }

    private fun processBuffer(buffer: FloatArray): PitchResult {
        val rmsDb = AudioUtils.linearToDb(AudioUtils.rms(buffer))
        if (rmsDb < silenceThresholdDb) return PitchResult.SILENT

        val start = System.nanoTime()
        val result = pitchDetector.detectPitch(buffer = buffer, lowestFrequency = lowestFrequency)
        val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
        if (elapsed in 0..500) inferenceLatencyMs = elapsed
        if (result.frequency <= 0f) return PitchResult.SILENT

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
}
