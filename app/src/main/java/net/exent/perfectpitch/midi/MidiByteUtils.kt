package net.exent.perfectpitch.midi

/** Shared byte-reading helpers for Standard MIDI File (SMF) parsing. */

internal fun readMidiString(data: ByteArray, pos: Int, length: Int): String =
    String(data, pos, length, Charsets.US_ASCII)

internal fun readMidiInt32(data: ByteArray, pos: Int): Int =
    ((data[pos].toInt() and 0xFF) shl 24) or
    ((data[pos + 1].toInt() and 0xFF) shl 16) or
    ((data[pos + 2].toInt() and 0xFF) shl 8) or
    (data[pos + 3].toInt() and 0xFF)

internal fun readMidiInt16(data: ByteArray, pos: Int): Int =
    ((data[pos].toInt() and 0xFF) shl 8) or
    (data[pos + 1].toInt() and 0xFF)

internal fun readMidiVarLen(data: ByteArray, pos: Int): Pair<Long, Int> {
    var value = 0L
    var bytesRead = 0
    var current: Int
    do {
        if (pos + bytesRead >= data.size) break
        current = data[pos + bytesRead].toInt() and 0xFF
        value = (value shl 7) or (current.toLong() and 0x7F)
        bytesRead++
    } while (current and 0x80 != 0 && bytesRead < 4)
    return value to bytesRead
}
