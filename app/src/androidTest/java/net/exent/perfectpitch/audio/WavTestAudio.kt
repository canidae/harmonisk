package net.exent.perfectpitch.audio

import android.content.Context

/** Reads 16-bit PCM WAV assets used by the pitch detection tests. */
object WavTestAudio {

    fun load(context: Context, assetName: String): FloatArray =
        parse(context.assets.open(assetName).use { it.readBytes() })

    fun parse(data: ByteArray): FloatArray {
        var p = 0
        require(data[p++] == 'R'.code.toByte() && data[p++] == 'I'.code.toByte() &&
                data[p++] == 'F'.code.toByte() && data[p++] == 'F'.code.toByte()) { "Not a RIFF file" }
        p += 4
        require(data[p++] == 'W'.code.toByte() && data[p++] == 'A'.code.toByte() &&
                data[p++] == 'V'.code.toByte() && data[p++] == 'E'.code.toByte()) { "Not a WAVE file" }

        var numChannels = 0
        var bitsPerSample = 0

        while (p + 8 <= data.size) {
            val chunkId = data.sliceArray(p until p + 4).decodeToString()
            val chunkSize = readLeInt(data, p + 4)
            p += 8

            when (chunkId) {
                "fmt " -> {
                    require(readLeShort(data, p) == 1) { "Only PCM supported" }
                    numChannels = readLeShort(data, p + 2)
                    bitsPerSample = readLeShort(data, p + 14)
                    p += chunkSize
                }
                "data" -> {
                    val bps = bitsPerSample / 8
                    val ch = numChannels
                    val out = FloatArray(chunkSize / bps / ch)
                    var idx = 0
                    val end = p + chunkSize
                    while (p + bps * ch <= end && idx < out.size) {
                        var sum = 0L
                        for (c in 0 until ch) {
                            sum += when (bps) {
                                1 -> ((data[p].toInt() and 0xFF) - 128).toLong()
                                2 -> {
                                    val lo = data[p].toInt() and 0xFF
                                    val hi = data[p + 1].toInt() and 0xFF
                                    (lo or (hi shl 8)).toShort().toLong()
                                }
                                else -> 0L
                            }
                            p += bps
                        }
                        out[idx++] = (sum.toDouble() / (ch * 32768.0)).toFloat()
                    }
                    return out
                }
                else -> p += chunkSize
            }
        }
        error("No data chunk found in WAV file")
    }

    private fun readLeShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)
}
