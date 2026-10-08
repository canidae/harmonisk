package net.exent.harmonisk.midi

import android.util.Log
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.MAX_BPM
import net.exent.harmonisk.ui.MIN_BPM
import net.exent.harmonisk.ui.freeplay.PlayedNote
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
        val reader = MidiTrackReader(data)

        val header = reader.readHeader()

        var pos = reader.firstTrackPosition

        var bpm = 120f
        var beatsPerBar = 4
        val trackNotes = mutableListOf<List<NoteEvent>>()

        for (trackIdx in 0 until header.numTracks) {
            if (pos + 8 > data.size) break
            val chunkId = readMidiString(data, pos, 4)
            if (chunkId != "MTrk") { pos += 8; continue }
            val trackLength = readMidiInt32(data, pos + 4)
            val trackEnd = pos + 8 + trackLength

            val notes = mutableListOf<NoteEvent>()
            val callback = object : MidiTrackReader.EventCallback {
                override fun onNoteEvent(note: NoteEvent) { notes.add(note) }
                override fun onTempoEvent(tick: Long, usPerBeat: Long) {
                    if (usPerBeat > 0) bpm = (60_000_000.0 / usPerBeat).toFloat()
                }
                override fun onTimeSignature(tick: Long, numerator: Int, denominator: Int) {
                    beatsPerBar = numerator
                }
                override fun onEndOfTrack(tick: Long, activeNotes: List<MidiTrackReader.ActiveNote>) {
                    for (an in activeNotes) {
                        notes.add(NoteEvent(an.note, an.velocity, an.startTick, tick - an.startTick))
                    }
                }
            }

            pos = reader.readTrack(pos, trackEnd, callback, TAG)
            if (notes.isNotEmpty()) {
                trackNotes.add(notes.sortedBy { it.startTick })
            }
        }

        return MultiTrackMidi(
            bpm = bpm.coerceIn(MIN_BPM, MAX_BPM),
            beatsPerBar = beatsPerBar.coerceIn(1, 16),
            ticksPerQuarterNote = header.timeDivision.coerceAtLeast(1),
            voiceTracks = trackNotes
        )
    }

    fun convertToPlayedNotes(multiTrackMidi: MultiTrackMidi): MidiImportResult {
        val tpq = multiTrackMidi.ticksPerQuarterNote.toDouble()
        val bpmD = multiTrackMidi.bpm.toDouble()

        fun tickToMs(tick: Long): Long =
            (tick.toDouble() / tpq * 60_000.0 / bpmD).toLong()

        val playedNotes = mutableListOf<PlayedNote>()

        // Each MIDI track is one voice/staff; a track may contain chords (simultaneous notes).
        for ((voiceIdx, trackNotes) in multiTrackMidi.voiceTracks.withIndex()) {
            for (note in trackNotes) {
                val startMs = tickToMs(note.startTick)
                val endMs = tickToMs(note.endTick)
                if (endMs <= startMs) continue

                val resolution = NoteUtils.resolveMidiNote(note.noteNumber)

                playedNotes.add(PlayedNote(
                    noteName = resolution.noteName,
                    octave = resolution.octave,
                    midiNumber = note.noteNumber,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    id = UUID.randomUUID().toString(),
                    voiceIndex = voiceIdx,
                    trackOrigin = voiceIdx
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
