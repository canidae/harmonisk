package net.exent.perfectpitch.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
        /** Read chunk size — smaller for lower latency. Analysis uses a larger sliding window.
         *  512 samples = ~11.6ms per hop at 44100 Hz. Smaller hops mean the sliding window
         *  refreshes more frequently, reducing the duration of transition/mixed-note frames. */
        const val BUFFER_SIZE_SAMPLES = 512

        /**
         * VOICE_PERFORMANCE (API 29+): Designed for live performance / karaoke.
         * Minimizes latency and decouples from playback path — ideal for instrument pitch detection.
         */
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_PERFORMANCE
    }

    private val minBufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    )

    private val bufferSize = maxOf(
        minBufferSize,
        BUFFER_SIZE_SAMPLES * 4 // 4 bytes per float
    )

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

        withContext(Dispatchers.IO) {
            val buffer = FloatArray(BUFFER_SIZE_SAMPLES)

            while (isActive) {
                val read = audioRecord.read(
                    buffer, 0, BUFFER_SIZE_SAMPLES, AudioRecord.READ_BLOCKING
                )
                if (read > 0) {
                    trySend(buffer.copyOf(read))
                }
            }
        }

        awaitClose {
            audioRecord.stop()
            audioRecord.release()
        }
    }
}
