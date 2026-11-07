package net.exent.harmonisk.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

internal class SoundPoolPlayer(
    context: Context,
    maxStreams: Int,
    resources: Collection<Int>
) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(maxStreams)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val samples = resources.associateWith { pool.load(context, it, 1) }

    fun play(resource: Int, volume: Float) {
        samples[resource]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    fun loop(resource: Int, volume: Float): Int =
        samples[resource]?.let { pool.play(it, volume, volume, 0, -1, 1f) } ?: 0

    fun stop(streamId: Int) {
        if (streamId != 0) pool.stop(streamId)
    }

    fun release() = pool.release()
}
