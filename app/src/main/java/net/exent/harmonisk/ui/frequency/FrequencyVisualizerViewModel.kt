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
import net.exent.harmonisk.audio.PitchDebouncer
import net.exent.harmonisk.audio.PitchDetector
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.WavFileWriter
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
    private val detector: PitchDetector
) : ViewModel() {

    private data class AudioChunk(val samples: FloatArray, val startSamples: Long)

    private companion object {
        const val PUBLISH_INTERVAL_NANOS = 100_000_000L
        const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
        const val FRAME_INTERVAL_SAMPLES = SAMPLE_RATE / 30
        const val MAX_GRAPH_FRAMES = 1024
    }

    private val _state = MutableStateFlow(FrequencyVisualizerState())
    val state: StateFlow<FrequencyVisualizerState> = _state.asStateFlow()

    private val window = FloatArray(PitchProcessor.ANALYSIS_WINDOW_SIZE)
    private val frames = mutableListOf<FrequencyTraceFrame>()
    private val graphFrames = mutableListOf<FrequencyTraceFrame>()
    private var lastDetectSamples = 0L
    private var windowFilled = 0
    private var lastPublishNanos = 0L
    private var lastFrameTimeMs = 0L
    private var firstPitchMs: Long? = null
    private var lastPitchMs: Long? = null
    private val debouncer = PitchDebouncer(requiredFrames = 2, silenceFrames = 4)
    private val lowestFrequency = PitchDetector.MIN_FREQUENCY
    private val highestFrequency = PitchDetector.MAX_FREQUENCY
    private var referenceFrequency = 0f
    private var silentFrames = 0
    private var lastValidFrequency = 0f
    private var lastValidConfidence = 0f

    private var captureJob: Job? = null
    private var analysisJob: Job? = null
    private var queue: Channel<AudioChunk>? = null
    private var wavWriter: WavFileWriter? = null
    private var exportFile: File? = null

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
        graphFrames.clear()
        windowFilled = 0
        lastDetectSamples = 0L
        lastPublishNanos = 0L
        lastFrameTimeMs = 0L
        firstPitchMs = null
        lastPitchMs = null
        debouncer.reset()
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
            val displayFrames = downsample(trimmedFrames)
            exportFile = exported
            val hasPitch = startMs != null && endMs != null
            val durationMs = if (startMs != null && endMs != null) endMs - startMs else 0L
            _state.update {
                it.copy(
                    finalizing = false,
                    frames = displayFrames,
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
            frames.clear()
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
                if (windowFilled == window.size) maybeDetect(chunk.startSamples + offset)
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
            maybeDetect(chunk.startSamples + offset)
        }
    }

    private fun detect(endSamples: Long) {
        val result = detector.detectPitch(
            window,
            lowestFrequency,
            referenceFrequency,
            highestFrequency
        )
        val frequency = if (result.frequency > 0f) {
            debouncer.filter(result.frequency)
        } else {
            debouncer.filter(0f)
            0f
        }
        if (frequency > 0f) {
            silentFrames = 0
        } else {
            silentFrames++
            if (silentFrames >= 2) referenceFrequency = 0f
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
        appendFrame(frame)
        publish()
    }

    private fun maybeDetect(endSamples: Long) {
        if (lastDetectSamples != 0L && endSamples - lastDetectSamples < FRAME_INTERVAL_SAMPLES) return
        lastDetectSamples = endSamples
        detect(endSamples)
    }

    private fun appendFrame(frame: FrequencyTraceFrame) {
        frames.add(frame)
        graphFrames.add(frame)
        if (graphFrames.size <= MAX_GRAPH_FRAMES) return
        var write = 0
        var read = 0
        while (read < graphFrames.size - 1) {
            graphFrames[write++] = graphFrames[read]
            read += 2
        }
        graphFrames[write++] = graphFrames.last()
        graphFrames.subList(write, graphFrames.size).clear()
    }

    private fun downsample(source: List<FrequencyTraceFrame>): List<FrequencyTraceFrame> {
        if (source.size <= MAX_GRAPH_FRAMES) return source
        val stride = (source.size + MAX_GRAPH_FRAMES - 1) / MAX_GRAPH_FRAMES
        val result = ArrayList<FrequencyTraceFrame>(MAX_GRAPH_FRAMES + 1)
        var index = 0
        while (index < source.size) {
            result.add(source[index])
            index += stride
        }
        if (result.last() !== source.last()) result.add(source.last())
        return result
    }

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = now
        val start = firstPitchMs
        _state.update {
            it.copy(
                frames = graphFrames.toList(),
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
