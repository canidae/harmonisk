package net.exent.perfectpitch.midi

import android.util.Log
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.ui.freeplay.PlayedNote
import java.io.InputStream
import java.util.UUID

data class MultiTrackMidi(
    val bpm: Float,
    val beatsPerBar: Int,
    val ticksPerQuarterNote: Int,
    val voiceTracks: List<List<NoteEvent>>
)

data class MidiImportResult(
    val notes: List<PlayedNote>,
    val bpm: Float,
    val beatsPerBar: Int
)

object MidiImporter {

    private const val TAG = "MidiImporter"

    fun parse(inputStream: InputStream): MultiTrackMidi {
        val data = inputStream.readBytes()
        var pos = 0

        val headerChunkId = readMidiString(data, pos, 4)
        require(headerChunkId == "MThd") { "Not a MIDI file" }
        pos += 4

        val headerLength = readMidiInt32(data, pos); pos += 4
        val format = readMidiInt16(data, pos); pos += 2
        val numTracks = readMidiInt16(data, pos); pos += 2
        val timeDivision = readMidiInt16(data, pos); pos += 2
        pos = 8 + headerLength

        var bpm = 120f
        var beatsPerBar = 4
        val trackNotes = mutableListOf<List<NoteEvent>>()

        for (trackIdx in 0 until numTracks) {
            if (pos + 8 > data.size) break
            val chunkId = readMidiString(data, pos, 4)
            if (chunkId != "MTrk") { pos += 8; continue }
            pos += 4

            val trackLength = readMidiInt32(data, pos); pos += 4
            val trackEnd = pos + trackLength
            var tick = 0L
            var runningStatus = 0
            val activeNotes = mutableMapOf<Int, Pair<Long, Int>>()
            val notes = mutableListOf<NoteEvent>()

            while (pos < trackEnd) {
                if (pos >= data.size) break
                val (delta, bytesRead) = readMidiVarLen(data, pos)
                pos += bytesRead
                tick += delta
                if (pos >= data.size) break

                var statusByte = data[pos].toInt() and 0xFF
                if (statusByte < 0x80) {
                    statusByte = runningStatus
                } else {
                    pos++
                    if (statusByte < 0xF0) runningStatus = statusByte
                }

                when {
                    statusByte in 0x80..0x8F -> {
                        val note = data[pos++].toInt() and 0x7F
                        pos++
                        activeNotes.remove(note)?.let { (st, vel) ->
                            notes.add(NoteEvent(note, vel, st, tick - st))
                        }
                    }
                    statusByte in 0x90..0x9F -> {
                        val note = data[pos++].toInt() and 0x7F
                        val vel = data[pos++].toInt() and 0x7F
                        if (vel == 0) {
                            activeNotes.remove(note)?.let { (st, v) ->
                                notes.add(NoteEvent(note, v, st, tick - st))
                            }
                        } else {
                            activeNotes[note] = tick to vel
                        }
                    }
                    statusByte in 0xA0..0xAF -> { pos += 2 }
                    statusByte in 0xB0..0xBF -> { pos += 2 }
                    statusByte in 0xC0..0xCF -> { pos += 1 }
                    statusByte in 0xD0..0xDF -> { pos += 1 }
                    statusByte in 0xE0..0xEF -> { pos += 2 }
                    statusByte == 0xFF -> {
                        if (pos >= data.size) break
                        val metaType = data[pos++].toInt() and 0xFF
                        val (metaLen, metaLenBytes) = readMidiVarLen(data, pos)
                        pos += metaLenBytes
                        when (metaType) {
                            0x51 -> {
                                if (pos + 2 < data.size) {
                                    val us = ((data[pos].toLong() and 0xFF) shl 16) or
                                        ((data[pos + 1].toLong() and 0xFF) shl 8) or
                                        (data[pos + 2].toLong() and 0xFF)
                                    if (us > 0) bpm = (60_000_000.0 / us).toFloat()
                                }
                            }
                            0x58 -> {
                                if (pos < data.size) beatsPerBar = data[pos].toInt() and 0xFF
                            }
                            0x2F -> {
                                for ((note, pair) in activeNotes) {
                                    notes.add(NoteEvent(note, pair.second, pair.first, tick - pair.first))
                                }
                                activeNotes.clear()
                            }
                        }
                        pos += metaLen.toInt()
                    }
                    statusByte == 0xF0 || statusByte == 0xF7 -> {
                        val (len, lb) = readMidiVarLen(data, pos)
                        pos += lb + len.toInt()
                    }
                    else -> {
                        Log.w(TAG, "Unknown MIDI event: 0x${statusByte.toString(16)} at pos $pos, skipping rest of track")
                        break
                    }
                }
            }
            pos = trackEnd

            if (notes.isNotEmpty()) {
                trackNotes.add(notes.sortedBy { it.startTick })
            }
        }

        return MultiTrackMidi(
            bpm = bpm.coerceIn(20f, 300f),
            beatsPerBar = beatsPerBar.coerceIn(1, 16),
            ticksPerQuarterNote = timeDivision.coerceAtLeast(1),
            voiceTracks = trackNotes
        )
    }

    fun convertToPlayedNotes(multiTrackMidi: MultiTrackMidi): MidiImportResult {
        val tpq = multiTrackMidi.ticksPerQuarterNote.toDouble()
        val bpmD = multiTrackMidi.bpm.toDouble()

        fun tickToMs(tick: Long): Long =
            (tick.toDouble() / tpq * 60_000.0 / bpmD).toLong()

        val playedNotes = mutableListOf<PlayedNote>()
        for ((voiceIdx, voiceNotes) in multiTrackMidi.voiceTracks.withIndex()) {
            for (note in voiceNotes) {
                val startMs = tickToMs(note.startTick)
                val endMs = tickToMs(note.endTick)
                if (endMs <= startMs) continue

                val noteIdx = ((note.noteNumber % 12) + 12) % 12
                val octave = (note.noteNumber / 12) - 1
                val noteName = NoteUtils.NOTE_NAMES[noteIdx]

                playedNotes.add(PlayedNote(
                    noteName = noteName,
                    octave = octave,
                    midiNumber = note.noteNumber,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    averageCentsOff = 0f,
                    id = UUID.randomUUID().toString(),
                    voiceIndex = voiceIdx
                ))
            }
        }
        return MidiImportResult(
            notes = playedNotes,
            bpm = multiTrackMidi.bpm,
            beatsPerBar = multiTrackMidi.beatsPerBar
        )
    }
}
