package com.perfectpitch.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioCaptureService(
    private val context: Context
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
        /** Read chunk size — smaller for lower latency. Analysis uses a larger sliding window. */
        const val BUFFER_SIZE_SAMPLES = 1024

        /**
         * VOICE_PERFORMANCE (API 29+): Designed for live performance / karaoke.
         * Minimizes latency and decouples from playback path — ideal for instrument pitch detection.
         */
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_PERFORMANCE

        /** Half of a 1024-sample read chunk (~11.6ms). Used as algorithm offset. */
        const val ALGORITHM_OFFSET_MS = 12f

        /** Number of measurements to average for pipeline delay smoothing. */
        private const val DELAY_WINDOW_SIZE = 10
        /** Minimum valid measurements before publishing pipeline delay. */
        private const val MIN_VALID_MEASUREMENTS = 3
    }

    private val minBufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    )

    private val bufferSize = maxOf(
        minBufferSize,
        BUFFER_SIZE_SAMPLES * 4 // 4 bytes per float
    )

    /**
     * Measured input pipeline delay (ms) from AudioRecord.getTimestamp().
     * This is the delay from mic hardware capture to our read() call returning.
     * null if not yet measured or timestamps unavailable on this device.
     */
    private val _measuredPipelineDelayMs = MutableStateFlow<Float?>(null)
    val measuredPipelineDelayMs: StateFlow<Float?> = _measuredPipelineDelayMs.asStateFlow()

    fun audioFlow(): Flow<FloatArray> = callbackFlow {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(AUDIO_SOURCE)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setContext(context)
            .build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        audioRecord.startRecording()
        _measuredPipelineDelayMs.value = null

        withContext(Dispatchers.IO) {
            val buffer = FloatArray(BUFFER_SIZE_SAMPLES)
            var totalFramesRead = 0L
            val timestamp = AudioTimestamp()
            val recentDelays = FloatArray(DELAY_WINDOW_SIZE)
            var delayIndex = 0
            var validMeasurements = 0

            while (isActive) {
                val read = audioRecord.read(
                    buffer, 0, BUFFER_SIZE_SAMPLES, AudioRecord.READ_BLOCKING
                )
                if (read > 0) {
                    totalFramesRead += read
                    measurePipelineDelay(
                        audioRecord, timestamp, totalFramesRead,
                        recentDelays, delayIndex, validMeasurements
                    ).let { (newIndex, newValid) ->
                        delayIndex = newIndex
                        validMeasurements = newValid
                    }
                    trySend(buffer.copyOf(read))
                }
            }
        }

        awaitClose {
            audioRecord.stop()
            audioRecord.release()
            _measuredPipelineDelayMs.value = null
        }
    }

    /**
     * Measure the input pipeline delay using AudioRecord.getTimestamp().
     * Returns updated (delayIndex, validMeasurements).
     */
    private fun measurePipelineDelay(
        audioRecord: AudioRecord,
        timestamp: AudioTimestamp,
        totalFramesRead: Long,
        recentDelays: FloatArray,
        delayIndex: Int,
        validMeasurements: Int
    ): Pair<Int, Int> {
        val result = audioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
        if (result != AudioRecord.SUCCESS) return Pair(delayIndex, validMeasurements)

        val now = SystemClock.elapsedRealtimeNanos()

        // Extrapolate: how far ahead of the timestamp anchor are we?
        val framesAhead = totalFramesRead - timestamp.framePosition
        if (framesAhead < 0) return Pair(delayIndex, validMeasurements)

        val timeOfLastFrame = timestamp.nanoTime +
            framesAhead * 1_000_000_000L / SAMPLE_RATE
        val delayNs = now - timeOfLastFrame
        if (delayNs < 0) return Pair(delayIndex, validMeasurements)

        val delayMs = delayNs / 1_000_000f
        // Sanity: discard if > 500ms (clearly wrong)
        if (delayMs > 500f) return Pair(delayIndex, validMeasurements)

        // Rolling average
        val newIndex = delayIndex % recentDelays.size
        recentDelays[newIndex] = delayMs
        val newValid = (validMeasurements + 1).coerceAtMost(recentDelays.size)

        if (newValid >= MIN_VALID_MEASUREMENTS) {
            val avg = recentDelays.take(newValid).average().toFloat()
            _measuredPipelineDelayMs.value = avg
        }

        return Pair(newIndex + 1, newValid)
    }
}
