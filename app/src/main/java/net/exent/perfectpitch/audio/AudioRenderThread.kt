package net.exent.perfectpitch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioRenderThread(
    private val threadName: String,
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
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferFrames * 2, minBufferSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack = track
        track.play()
        running = true

        renderThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(bufferFrames)
            while (running) {
                render(buffer)
                try {
                    track.write(buffer, 0, buffer.size)
                } catch (_: Exception) {}
            }
        }, threadName).apply { start() }
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
}
