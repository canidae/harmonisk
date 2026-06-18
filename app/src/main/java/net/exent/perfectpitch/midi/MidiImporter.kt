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
            bpm = bpm.coerceIn(20f, 300f),
            beatsPerBar = beatsPerBar.coerceIn(1, 16),
            ticksPerQuarterNote = header.timeDivision.coerceAtLeast(1),
            voiceTracks = trackNotes
        )
    }

    fun convertToPlayedNotes(multiTrackMidi: MultiTrackMidi): MidiImportResult {
        val tpq = multiTrackMidi.ticksPerQuarterNote.toDouble()
        val bpmD = multiTrackMidi.bpm.toDouble()
        val usPerBeat = (60_000_000.0 / bpmD).toLong()

        fun tickToMs(tick: Long): Long =
            (tick.toDouble() / tpq * 60_000.0 / bpmD).toLong()

        val playedNotes = mutableListOf<PlayedNote>()
        var globalVoiceIdx = 0

        for ((trackOrigin, voiceNotes) in multiTrackMidi.voiceTracks.withIndex()) {
            // Build a temporary MelodyTrack so we can use splitIntoVoices()
            val tmpTrack = MelodyTrack(
                name = "",
                notes = voiceNotes,
                tempoChanges = listOf(TempoEvent(0, usPerBeat)),
                timeSignatures = emptyList(),
                ticksPerQuarterNote = multiTrackMidi.ticksPerQuarterNote
            )
            val splitTracks = tmpTrack.splitIntoVoices()
            for (voiceTrack in splitTracks) {
                for (note in voiceTrack.notes) {
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
                        voiceIndex = globalVoiceIdx,
                        trackOrigin = trackOrigin
                    ))
                }
                globalVoiceIdx++
            }
        }
        return MidiImportResult(
            notes = playedNotes,
            bpm = multiTrackMidi.bpm,
            beatsPerBar = multiTrackMidi.beatsPerBar
        )
    }
}
