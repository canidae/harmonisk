package net.exent.harmonisk.audio

import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class PitchProcessor(
    private val audioCaptureService: AudioCaptureService,
    private val pitchDetector: PitchDetector,
    private val noteUtils: NoteUtils
) {
    companion object {
        const val SILENCE_THRESHOLD_DB = AudioConstants.DEFAULT_SILENCE_THRESHOLD_DB

        const val ANALYSIS_WINDOW_SIZE = 2822

        const val ANALYSIS_WINDOW_MS = (ANALYSIS_WINDOW_SIZE * 1000 + AudioConstants.SAMPLE_RATE / 2) / AudioConstants.SAMPLE_RATE

        private const val ONSET_DB_THRESHOLD = 5f
        private const val ONSET_DEBOUNCE_CHUNKS = 2

        private const val LOWEST_NOTE_MARGIN_CENTS = 200f

        private const val HIGHEST_NOTE_MARGIN_CENTS = 200f

        internal fun lowestDetectableFrequency(instrument: Instrument, referenceA4: Float): Float =
            NoteUtils.midiToFrequency(instrument.tuning.first(), referenceA4) *
                2.0.pow(-LOWEST_NOTE_MARGIN_CENTS / 1200.0).toFloat()

        internal fun highestDetectableFrequency(instrument: Instrument, referenceA4: Float): Float =
            NoteUtils.midiToFrequency(instrument.highestPlayableMidi, referenceA4) *
                2.0.pow(HIGHEST_NOTE_MARGIN_CENTS / 1200.0).toFloat()
    }

    private var silenceThresholdDb = SILENCE_THRESHOLD_DB
    private var referenceA4 = NoteUtils.DEFAULT_CONCERT_PITCH
    private var instrument = Instrument.VIOLIN
    @Volatile
    private var lowestFrequency = lowestDetectableFrequency(instrument, referenceA4)

    @Volatile
    private var highestFrequency = highestDetectableFrequency(instrument, referenceA4)

    fun setSilenceThreshold(db: Float) { silenceThresholdDb = db }

    fun setReferenceA4(frequency: Float) {
        referenceA4 = frequency
        updateFrequencyRange()
    }

    fun setInstrument(instrument: Instrument) {
        this.instrument = instrument
        updateFrequencyRange()
    }

    private fun updateFrequencyRange() {
        lowestFrequency = lowestDetectableFrequency(instrument, referenceA4)
        highestFrequency = highestDetectableFrequency(instrument, referenceA4)
    }

    fun pitchFlow(source: Flow<FloatArray> = audioCaptureService.audioFlow()): Flow<PitchResult> {
        val slidingWindow = FloatArray(ANALYSIS_WINDOW_SIZE)
        var windowFilled = 0
        var prevChunkRms = 0f
        var onsetStreak = 0
        var referenceFrequency = 0f
        val debouncer = PitchDebouncer()

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
                    referenceFrequency = 0f
                    slidingWindow.fill(0f)
                    System.arraycopy(chunk, 0, slidingWindow, 0, chunkSize)
                    windowFilled = chunkSize
                } else {
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
                else slidingWindow.copyOf()
            }
            .buffer(Channel.CONFLATED)
            .map { buffer ->
                processBuffer(buffer, referenceFrequency, debouncer).also { result ->
                    if (result.isValid) referenceFrequency = result.frequency
                }
            }
            .flowOn(Dispatchers.Default)
    }

    private fun processBuffer(
        buffer: FloatArray,
        referenceFrequency: Float,
        debouncer: PitchDebouncer
    ): PitchResult {
        val rmsDb = AudioUtils.linearToDb(AudioUtils.rms(buffer))
        if (rmsDb < silenceThresholdDb) {
            debouncer.filter(0f)
            return PitchResult.SILENT
        }

        val result = pitchDetector.detectPitch(
            buffer = buffer,
            lowestFrequency = lowestFrequency,
            referenceFrequency = referenceFrequency,
            highestFrequency = highestFrequency
        )
        if (result.frequency <= 0f) {
            debouncer.filter(0f)
            return PitchResult.SILENT
        }

        val frequency = debouncer.filter(result.frequency)
        val noteInfo = noteUtils.frequencyToNote(frequency, referenceA4)
        return PitchResult(
            frequency = frequency,
            noteName = noteInfo.name,
            octave = noteInfo.octave,
            centsOff = noteInfo.centsOff,
            midiNoteNumber = noteInfo.midiNumber,
            isSilent = false,
            confidence = result.confidence
        )
    }
}
