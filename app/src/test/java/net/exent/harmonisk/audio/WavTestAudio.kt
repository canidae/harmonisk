package net.exent.harmonisk.audio

import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem

internal object WavTestAudio {

    fun load(resource: String): FloatArray {
        val stream = WavTestAudio::class.java.getResourceAsStream(resource)
            ?: error("Missing test audio $resource")
        val audio = AudioSystem.getAudioInputStream(BufferedInputStream(stream))
        val format = audio.format
        require(format.sampleSizeInBits == 16 && format.channels == 1) {
            "Expected mono 16-bit PCM, got $format"
        }
        val bytes = audio.readBytes()
        val bigEndian = format.isBigEndian
        return FloatArray(bytes.size / 2) { i ->
            val first = bytes[2 * i].toInt() and 0xff
            val second = bytes[2 * i + 1].toInt() and 0xff
            val value = if (bigEndian) (first shl 8) or second else (second shl 8) or first
            value.toShort() / 32768f
        }
    }
}
