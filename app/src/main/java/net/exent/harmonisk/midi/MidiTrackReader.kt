package net.exent.harmonisk.midi

import android.util.Log
import net.exent.harmonisk.BuildConfig

internal class MidiTrackReader(private val data: ByteArray) {

    data class Header(
        val format: Int,
        val numTracks: Int,
        val timeDivision: Int
    )

    interface EventCallback {
        fun onNoteEvent(note: NoteEvent)
        fun onTempoEvent(tick: Long, usPerBeat: Long)
        fun onTimeSignature(tick: Long, numerator: Int, denominator: Int)
        fun onEndOfTrack(tick: Long, activeNotes: List<ActiveNote>)

        /** Key signature meta (0xFF 0x59): [fifths] is signed, positive for sharps. */
        fun onKeySignature(tick: Long, fifths: Int, isMinor: Boolean) {}
    }

    data class ActiveNote(val note: Int, val velocity: Int, val startTick: Long)

    fun readHeader(): Header {
        var pos = 0
        val headerChunkId = readMidiString(data, pos, 4)
        require(headerChunkId == "MThd") { "Invalid MIDI file: expected MThd, got $headerChunkId" }
        pos += 4

        val headerLength = readMidiInt32(data, pos)
        pos += 4

        val format = readMidiInt16(data, pos); pos += 2
        val numTracks = readMidiInt16(data, pos); pos += 2
        val timeDivision = readMidiInt16(data, pos); pos += 2

        return Header(format, numTracks, timeDivision)
    }

    val firstTrackPosition: Int
        get() = 8 + ((data[4].toInt() and 0xFF) shl 24 or
            (data[5].toInt() and 0xFF) shl 16 or
            (data[6].toInt() and 0xFF) shl 8 or
            (data[7].toInt() and 0xFF))

    fun readTrack(pos: Int, trackEnd: Int, callback: EventCallback, tag: String): Int {
        var p = pos
        val trackChunkId = readMidiString(data, p, 4)
        require(trackChunkId == "MTrk") { "Expected MTrk at position $p, got $trackChunkId" }
        p += 4

        val trackLength = readMidiInt32(data, p)
        p += 4

        val end = p + trackLength
        var tick = 0L
        var runningStatus = 0

        val activeNotes = mutableMapOf<Int, Pair<Long, Int>>()

        while (p < end) {
            val (deltaTime, bytesRead) = readMidiVarLen(data, p)
            p += bytesRead
            tick += deltaTime

            var statusByte = data[p].toInt() and 0xFF

            if (statusByte < 0x80) {
                statusByte = runningStatus
            } else {
                p++
                if (statusByte < 0xF0) {
                    runningStatus = statusByte
                }
            }

            when {
                statusByte in 0x80..0x8F -> {
                    val note = data[p++].toInt() and 0x7F
                    p++
                    activeNotes.remove(note)?.let { (startTick, velocity) ->
                        callback.onNoteEvent(NoteEvent(note, velocity, startTick, tick - startTick))
                    }
                }

                statusByte in 0x90..0x9F -> {
                    val note = data[p++].toInt() and 0x7F
                    val velocity = data[p++].toInt() and 0x7F
                    if (velocity == 0) {
                        activeNotes.remove(note)?.let { (startTick, vel) ->
                            callback.onNoteEvent(NoteEvent(note, vel, startTick, tick - startTick))
                        }
                    } else {
                        activeNotes[note] = tick to velocity
                    }
                }

                statusByte in 0xA0..0xAF -> { p += 2 }
                statusByte in 0xB0..0xBF -> { p += 2 }
                statusByte in 0xC0..0xCF -> { p += 1 }
                statusByte in 0xD0..0xDF -> { p += 1 }
                statusByte in 0xE0..0xEF -> { p += 2 }

                statusByte == 0xFF -> {
                    val metaType = data[p++].toInt() and 0xFF
                    val (metaLength, metaLenBytes) = readMidiVarLen(data, p)
                    p += metaLenBytes

                    when (metaType) {
                        0x51 -> {
                            val usPerBeat = ((data[p].toLong() and 0xFF) shl 16) or
                                ((data[p + 1].toLong() and 0xFF) shl 8) or
                                (data[p + 2].toLong() and 0xFF)
                            callback.onTempoEvent(tick, usPerBeat)
                        }
                        0x58 -> {
                            val numerator = data[p].toInt() and 0xFF
                            val denomPower = data[p + 1].toInt() and 0xFF
                            val denominator = 1 shl denomPower
                            callback.onTimeSignature(tick, numerator, denominator)
                        }
                        0x59 -> {
                            val fifths = data[p].toInt()
                            val isMinor = data[p + 1].toInt() != 0
                            callback.onKeySignature(tick, fifths, isMinor)
                        }
                        0x2F -> {
                            val toClose = activeNotes.map { (n, pair) ->
                                ActiveNote(n, pair.second, pair.first)
                            }
                            activeNotes.clear()
                            callback.onEndOfTrack(tick, toClose)
                        }
                    }
                    p += metaLength.toInt()
                }

                statusByte == 0xF0 || statusByte == 0xF7 -> {
                    val (sysexLength, sysexLenBytes) = readMidiVarLen(data, p)
                    p += sysexLenBytes + sysexLength.toInt()
                }

                else -> {
                    if (BuildConfig.DEBUG) Log.w(tag, "Unknown MIDI event: 0x${statusByte.toString(16)} at pos $p, skipping rest of track")
                    break
                }
            }
        }

        return end
    }
}
