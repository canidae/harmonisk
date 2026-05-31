package com.perfectpitch.midi

import android.util.Log
import java.io.InputStream

/** Parser for Standard MIDI File (SMF) format 0 and 1. */
class MidiParser {

    fun parse(inputStream: InputStream, name: String = "Unknown"): MelodyTrack {
        val data = inputStream.readBytes()
        var pos = 0

        // Parse header chunk
        val headerChunkId = readMidiString(data, pos, 4)
        require(headerChunkId == "MThd") { "Invalid MIDI file: expected MThd, got $headerChunkId" }
        pos += 4

        val headerLength = readMidiInt32(data, pos)
        pos += 4

        val format = readMidiInt16(data, pos)
        pos += 2
        val numTracks = readMidiInt16(data, pos)
        pos += 2
        val timeDivision = readMidiInt16(data, pos)
        pos += 2

        require(format in 0..1) { "Only MIDI format 0 and 1 are supported, got $format" }
        require(timeDivision > 0) { "Only ticks-per-quarter-note time division is supported" }

        // Skip any remaining header bytes (MThd=4 + lengthField=4 + headerLength)
        pos = 8 + headerLength

        // Parse all tracks
        val allNotes = mutableListOf<NoteEvent>()
        val allTempos = mutableListOf<TempoEvent>()
        val allTimeSignatures = mutableListOf<TimeSignatureEvent>()

        for (trackIdx in 0 until numTracks) {
            val trackChunkId = readMidiString(data, pos, 4)
            require(trackChunkId == "MTrk") { "Expected MTrk at position $pos, got $trackChunkId" }
            pos += 4

            val trackLength = readMidiInt32(data, pos)
            pos += 4

            val trackEnd = pos + trackLength
            var tick = 0L
            var runningStatus = 0

            // Track active notes for pairing Note On/Off
            val activeNotes = mutableMapOf<Int, Pair<Long, Int>>() // note -> (startTick, velocity)

            while (pos < trackEnd) {
                // Read delta time
                val (deltaTime, bytesRead) = readMidiVarLen(data, pos)
                pos += bytesRead
                tick += deltaTime

                // Read event
                var statusByte = data[pos].toInt() and 0xFF

                if (statusByte < 0x80) {
                    statusByte = runningStatus
                } else {
                    pos++
                    if (statusByte < 0xF0) {
                        runningStatus = statusByte
                    }
                }

                when {
                    // Note Off (0x8n)
                    statusByte in 0x80..0x8F -> {
                        val note = data[pos++].toInt() and 0x7F
                        pos++ // velocity (unused for note off)
                        activeNotes.remove(note)?.let { (startTick, velocity) ->
                            allNotes.add(NoteEvent(note, velocity, startTick, tick - startTick))
                        }
                    }

                    // Note On (0x9n)
                    statusByte in 0x90..0x9F -> {
                        val note = data[pos++].toInt() and 0x7F
                        val velocity = data[pos++].toInt() and 0x7F
                        if (velocity == 0) {
                            // Note On with velocity 0 = Note Off
                            activeNotes.remove(note)?.let { (startTick, vel) ->
                                allNotes.add(NoteEvent(note, vel, startTick, tick - startTick))
                            }
                        } else {
                            activeNotes[note] = tick to velocity
                        }
                    }

                    // Polyphonic aftertouch (0xAn)
                    statusByte in 0xA0..0xAF -> { pos += 2 }

                    // Control change (0xBn)
                    statusByte in 0xB0..0xBF -> { pos += 2 }

                    // Program change (0xCn)
                    statusByte in 0xC0..0xCF -> { pos += 1 }

                    // Channel aftertouch (0xDn)
                    statusByte in 0xD0..0xDF -> { pos += 1 }

                    // Pitch bend (0xEn)
                    statusByte in 0xE0..0xEF -> { pos += 2 }

                    // Meta event (0xFF)
                    statusByte == 0xFF -> {
                        val metaType = data[pos++].toInt() and 0xFF
                        val (metaLength, metaLenBytes) = readMidiVarLen(data, pos)
                        pos += metaLenBytes

                        when (metaType) {
                            0x51 -> {
                                // Tempo change
                                val usPerBeat = ((data[pos].toLong() and 0xFF) shl 16) or
                                    ((data[pos + 1].toLong() and 0xFF) shl 8) or
                                    (data[pos + 2].toLong() and 0xFF)
                                allTempos.add(TempoEvent(tick, usPerBeat))
                            }
                            0x58 -> {
                                // Time signature
                                val numerator = data[pos].toInt() and 0xFF
                                val denomPower = data[pos + 1].toInt() and 0xFF
                                val denominator = 1 shl denomPower
                                allTimeSignatures.add(TimeSignatureEvent(tick, numerator, denominator))
                            }
                            0x2F -> {
                                // End of track - close any remaining active notes
                                for ((note, pair) in activeNotes) {
                                    allNotes.add(NoteEvent(note, pair.second, pair.first, tick - pair.first))
                                }
                                activeNotes.clear()
                            }
                        }
                        pos += metaLength.toInt()
                    }

                    // SysEx (0xF0, 0xF7)
                    statusByte == 0xF0 || statusByte == 0xF7 -> {
                        val (sysexLength, sysexLenBytes) = readMidiVarLen(data, pos)
                        pos += sysexLenBytes + sysexLength.toInt()
                    }

                    else -> {
                        Log.w(TAG, "Unknown MIDI event: 0x${statusByte.toString(16)} at pos $pos, skipping rest of track")
                        break
                    }
                }
            }

            pos = trackEnd
        }

        allNotes.sortBy { it.startTick }
        if (allTempos.isEmpty()) allTempos.add(TempoEvent(0, 500_000))

        return MelodyTrack(
            name = name,
            notes = allNotes,
            tempoChanges = allTempos,
            timeSignatures = allTimeSignatures,
            ticksPerQuarterNote = timeDivision
        )
    }

    companion object {
        private const val TAG = "MidiParser"
    }
}
