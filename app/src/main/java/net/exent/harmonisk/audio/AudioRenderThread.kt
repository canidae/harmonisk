package net.exent.harmonisk.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioRenderThread(
    private val threadName: String,
    private val sampleRate: Int,
    private val contentType: Int,
    private val bufferFrames: Int,
    private val render: (ShortArray) -> Unit
) {
    @Volatile
    var running: Boolean = false
        private set

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null

    fun open() {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // getMinBufferSize reports a doubled minimum for mono; a quarter of it is the fast mixer's
        // minimum, which is what keeps the track off the resampling path and out of a deep buffer.
        val fastTrackBufferBytes = minBufferSize / 4 / BYTES_PER_FRAME * BYTES_PER_FRAME
        val track = createTrack(maxOf(bufferFrames * BYTES_PER_FRAME, fastTrackBufferBytes))
            ?: createTrack(minBufferSize)
            ?: return

        audioTrack = track
        val buffer = ShortArray(bufferFrames)
        render(buffer)
        track.write(buffer, 0, buffer.size)
        track.play()
        running = true

        renderThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                render(buffer)
                try {
                    track.write(buffer, 0, buffer.size)
                } catch (_: Exception) {}
            }
        }, threadName).apply { start() }
    }

    private fun createTrack(bufferSizeInBytes: Int): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    } catch (_: Exception) {
        null
    }

    fun close() {
        running = false
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {}
        renderThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private companion object {
        const val BYTES_PER_FRAME = 2
    }
}
