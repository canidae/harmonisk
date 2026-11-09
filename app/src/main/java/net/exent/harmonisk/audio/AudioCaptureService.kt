package net.exent.harmonisk.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.ActivityCompat
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive

class AudioCaptureService(
    private val context: Context
) {
    companion object {
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_PERFORMANCE
    }

    private val minBufferSize = AudioRecord.getMinBufferSize(
        AudioConstants.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    )

    @Volatile
    var captureLatencyMs: Int = 0
        private set

    private var totalFramesRead: Long = 0L

    private fun updateCaptureLatency(record: AudioRecord) {
        val ts = AudioTimestamp()
        if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            val nsSinceCapture = System.nanoTime() - ts.nanoTime
            val framesBehind = (ts.framePosition - totalFramesRead).coerceAtLeast(0)
            val ms = nsSinceCapture / 1_000_000.0 + framesBehind * 1000.0 / AudioConstants.SAMPLE_RATE
            val rounded = ms.toInt()
            if (rounded in 0..200) captureLatencyMs = rounded
        }
    }

    fun audioFlow(): Flow<FloatArray> = callbackFlow {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        val builder = AudioRecord.Builder()
            .setAudioSource(AUDIO_SOURCE)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(context)
        val audioRecord = builder.build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        audioRecord.startRecording()
        val readLoop = launch(Dispatchers.IO) {
            try {
                val buffer = FloatArray(AudioConstants.BUFFER_SIZE_SAMPLES)
                while (isActive) {
                    val read = runCatching {
                        audioRecord.read(buffer, 0, AudioConstants.BUFFER_SIZE_SAMPLES, AudioRecord.READ_BLOCKING)
                    }.getOrElse { break }
                    if (read > 0) {
                        totalFramesRead += read
                        updateCaptureLatency(audioRecord)
                        trySend(buffer.copyOf(read))
                    }
                }
            } finally {
                close()
            }
        }

        awaitClose {
            readLoop.cancel()
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }
}
