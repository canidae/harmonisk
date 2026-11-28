package net.exent.harmonisk.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
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
import java.util.concurrent.CopyOnWriteArrayList

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

    private val sampleListeners = CopyOnWriteArrayList<(FloatArray) -> Unit>()

    fun addSampleListener(listener: (FloatArray) -> Unit) {
        sampleListeners.addIfAbsent(listener)
    }

    fun removeSampleListener(listener: (FloatArray) -> Unit) {
        sampleListeners.remove(listener)
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
                        val samples = buffer.copyOf(read)
                        for (listener in sampleListeners) listener(samples)
                        trySend(samples)
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
