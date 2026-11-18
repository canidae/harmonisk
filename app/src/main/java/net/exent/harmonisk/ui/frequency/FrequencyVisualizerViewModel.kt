package net.exent.harmonisk.ui.frequency

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.AudioCaptureService
import net.exent.harmonisk.audio.AudioConstants
import net.exent.harmonisk.audio.FrequencyTraceExporter
import net.exent.harmonisk.audio.FrequencyTraceFrame
import net.exent.harmonisk.audio.PitchDetector
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.WavFileWriter
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import java.io.File
import javax.inject.Inject

data class FrequencyVisualizerState(
    val recording: Boolean = false,
    val finalizing: Boolean = false,
    val frames: List<FrequencyTraceFrame> = emptyList(),
    val startTimeMs: Long? = null,
    val elapsedMs: Long = 0L,
    val lastFrequency: Float = 0f,
    val lastConfidence: Float = 0f,
    val exportReady: Boolean = false,
    val noPitch: Boolean = false,
    val error: FrequencyVisualizerError? = null
)

enum class FrequencyVisualizerError {
    RECORDING_FAILED,
    EXPORT_FAILED
}

@HiltViewModel
class FrequencyVisualizerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capture: AudioCaptureService,
    private val detector: PitchDetector,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private data class AudioChunk(val samples: FloatArray, val startSamples: Long)

    private companion object {
        const val PUBLISH_INTERVAL_NANOS = 100_000_000L
        const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
    }

    private val _state = MutableStateFlow(FrequencyVisualizerState())
    val state: StateFlow<FrequencyVisualizerState> = _state.asStateFlow()

    private val window = FloatArray(PitchProcessor.ANALYSIS_WINDOW_SIZE)
    private val frames = mutableListOf<FrequencyTraceFrame>()
    private var windowFilled = 0
    private var lastPublishNanos = 0L
    private var lastFrameTimeMs = 0L
    private var firstPitchMs: Long? = null
    private var lastPitchMs: Long? = null
    private var referenceFrequency = 0f
    private var silentFrames = 0
    private var lastValidFrequency = 0f
    private var lastValidConfidence = 0f

    private var captureJob: Job? = null
    private var analysisJob: Job? = null
    private var queue: Channel<AudioChunk>? = null
    private var wavWriter: WavFileWriter? = null
    private var exportFile: File? = null

    @Volatile
    private var lowestFrequency = 35f

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                lowestFrequency = PitchProcessor.lowestDetectableFrequency(
                    Instrument.fromName(settings.instrumentName),
                    settings.concertPitchA4
                )
            }
        }
    }

    fun start() {
        if (_state.value.recording || _state.value.finalizing) return
        val directory = File(context.cacheDir, "frequency_visualizer").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val writer = WavFileWriter(File(directory, "recording.wav"))
        if (!writer.start()) {
            _state.update { it.copy(error = FrequencyVisualizerError.RECORDING_FAILED) }
            return
        }
        wavWriter = writer
        frames.clear()
        windowFilled = 0
        lastPublishNanos = 0L
        lastFrameTimeMs = 0L
        firstPitchMs = null
        lastPitchMs = null
        referenceFrequency = 0f
        silentFrames = 0
        lastValidFrequency = 0f
        lastValidConfidence = 0f
        exportFile = null
        val channel = Channel<AudioChunk>(Channel.UNLIMITED)
        queue = channel
        _state.value = FrequencyVisualizerState(recording = true)
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            for (chunk in channel) {
                runCatching { analyze(chunk) }.onFailure {
                    _state.update { state ->
                        if (state.error == null) {
                            state.copy(error = FrequencyVisualizerError.RECORDING_FAILED)
                        } else {
                            state
                        }
                    }
                }
            }
            publish(force = true)
        }
        captureJob = viewModelScope.launch(Dispatchers.Default) {
            var totalSamples = 0L
            try {
                capture.audioFlow()
                    .buffer(Channel.UNLIMITED)
                    .collect { samples ->
                        writer.write(samples)
                        val startSamples = totalSamples
                        totalSamples += samples.size
                        channel.trySend(AudioChunk(samples, startSamples))
                    }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _state.update { it.copy(error = FrequencyVisualizerError.RECORDING_FAILED) }
                }
            }
        }
    }

    fun stop() {
        if (!_state.value.recording || _state.value.finalizing) return
        _state.update { it.copy(recording = false, finalizing = true) }
        viewModelScope.launch {
            captureJob?.cancel()
            captureJob?.join()
            captureJob = null
            queue?.close()
            queue = null
            analysisJob?.join()
            analysisJob = null
            val wav = wavWriter?.finish()
            wavWriter = null
            val startMs = firstPitchMs
            val endMs = lastPitchMs
            val trimmedFrames = if (startMs != null && endMs != null) {
                frames.filter { it.timeMs in startMs..endMs }
            } else {
                emptyList()
            }
            val exported = if (startMs != null && endMs != null && wav != null) {
                FrequencyTraceExporter.export(
                    context = context,
                    wav = wav,
                    frames = trimmedFrames.map { it.copy(timeMs = it.timeMs - startMs) },
                    startSample = startMs * SAMPLE_RATE / 1000L,
                    endSample = endMs * SAMPLE_RATE / 1000L
                )
            } else {
                null
            }
            exportFile = exported
            val hasPitch = startMs != null && endMs != null
            val durationMs = if (startMs != null && endMs != null) endMs - startMs else 0L
            _state.update {
                it.copy(
                    finalizing = false,
                    frames = trimmedFrames,
                    startTimeMs = startMs,
                    elapsedMs = durationMs,
                    exportReady = exported != null,
                    noPitch = !hasPitch,
                    error = if (hasPitch && exported == null) {
                        FrequencyVisualizerError.EXPORT_FAILED
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    fun exportFile(): File? = exportFile

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun analyze(chunk: AudioChunk) {
        val samples = chunk.samples
        var offset = 0
        while (offset < samples.size) {
            if (windowFilled < window.size) {
                val count = minOf(samples.size - offset, window.size - windowFilled)
                System.arraycopy(samples, offset, window, windowFilled, count)
                windowFilled += count
                offset += count
                if (windowFilled == window.size) detect(chunk.startSamples + offset)
                continue
            }
            val remaining = samples.size - offset
            if (remaining >= window.size) {
                System.arraycopy(samples, samples.size - window.size, window, 0, window.size)
                offset = samples.size
            } else {
                System.arraycopy(window, remaining, window, 0, window.size - remaining)
                System.arraycopy(samples, offset, window, window.size - remaining, remaining)
                offset = samples.size
            }
            detect(chunk.startSamples + offset)
        }
    }

    private fun detect(endSamples: Long) {
        val result = detector.detectPitch(window, lowestFrequency, referenceFrequency)
        val frequency = if (result.frequency > 0f) result.frequency else 0f
        if (frequency > 0f) {
            silentFrames = 0
        } else {
            silentFrames++
            if (silentFrames >= 5) referenceFrequency = 0f
        }
        val frame = FrequencyTraceFrame(
            timeMs = endSamples * 1000L / SAMPLE_RATE,
            frequencyHz = frequency,
            confidence = result.confidence
        )
        lastFrameTimeMs = frame.timeMs
        if (frequency > 0f) {
            if (firstPitchMs == null) {
                firstPitchMs = (frame.timeMs - PitchProcessor.ANALYSIS_WINDOW_MS).coerceAtLeast(0L)
            }
            lastPitchMs = frame.timeMs
            referenceFrequency = frequency
            lastValidFrequency = frequency
            lastValidConfidence = result.confidence
        } else if (firstPitchMs == null) {
            return
        }
        frames.add(frame)
        publish()
    }

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = now
        val start = firstPitchMs
        _state.update {
            it.copy(
                frames = frames.toList(),
                startTimeMs = start,
                elapsedMs = if (start != null) {
                    (lastFrameTimeMs - start).coerceAtLeast(0L)
                } else {
                    0L
                },
                lastFrequency = lastValidFrequency,
                lastConfidence = lastValidConfidence
            )
        }
    }

    override fun onCleared() {
        captureJob?.cancel()
        queue?.close()
        analysisJob?.cancel()
        wavWriter?.abort()
        wavWriter = null
    }
}
