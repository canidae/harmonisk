package net.exent.perfectpitch.midi

import java.io.InputStream

class MidiParser {

    fun parse(inputStream: InputStream, name: String = "Unknown"): MelodyTrack {
        val data = inputStream.readBytes()
        val reader = MidiTrackReader(data)

        val header = reader.readHeader()
        require(header.format in 0..1) { "Only MIDI format 0 and 1 are supported, got ${header.format}" }
        require(header.timeDivision > 0) { "Only ticks-per-quarter-note time division is supported" }

        val allNotes = mutableListOf<NoteEvent>()
        val allTempos = mutableListOf<TempoEvent>()
        val allTimeSignatures = mutableListOf<TimeSignatureEvent>()

        var pos = reader.firstTrackPosition

        val callback = object : MidiTrackReader.EventCallback {
            override fun onNoteEvent(note: NoteEvent) { allNotes.add(note) }
            override fun onTempoEvent(tick: Long, usPerBeat: Long) { allTempos.add(TempoEvent(tick, usPerBeat)) }
            override fun onTimeSignature(tick: Long, numerator: Int, denominator: Int) {
                allTimeSignatures.add(TimeSignatureEvent(tick, numerator, denominator))
            }
            override fun onEndOfTrack(tick: Long, activeNotes: List<MidiTrackReader.ActiveNote>) {
                for (an in activeNotes) {
                    allNotes.add(NoteEvent(an.note, an.velocity, an.startTick, tick - an.startTick))
                }
            }
        }

        val totalTracks = header.numTracks
        for (trackIdx in 0 until totalTracks) {
            val trackStart = pos
            // Read the track header to get its length
            require(readMidiString(data, pos, 4) == "MTrk") { "Expected MTrk at position $pos" }
            val trackLength = readMidiInt32(data, pos + 4)
            val trackEnd = pos + 8 + trackLength
            // Use the reader to process this track
            pos = reader.readTrack(pos, trackEnd, callback, TAG)
            require(pos == trackEnd) { "Track read mismatch" }
        }

        allNotes.sortBy { it.startTick }
        if (allTempos.isEmpty()) allTempos.add(TempoEvent(0, 500_000))

        return MelodyTrack(
            name = name,
            notes = allNotes,
            tempoChanges = allTempos,
            timeSignatures = allTimeSignatures,
            ticksPerQuarterNote = header.timeDivision
        )
    }

    companion object {
        private const val TAG = "MidiParser"
    }
}
