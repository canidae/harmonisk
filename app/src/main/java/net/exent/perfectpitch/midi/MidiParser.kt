package net.exent.perfectpitch.midi

import java.io.InputStream

class MidiParser {

    /**
     * Parse a MIDI file into a single consolidated [MelodyTrack].
     * All note events from all tracks are merged and sorted.
     */
    fun parse(inputStream: InputStream, name: String = "Unknown"): MelodyTrack {
        val data = inputStream.readBytes()
        val reader = MidiTrackReader(data)
        val header = reader.readHeader()
        require(header.format in 0..1) { "Only MIDI format 0 and 1 are supported, got ${header.format}" }
        require(header.timeDivision > 0) { "Only ticks-per-quarter-note time division is supported" }

        val tracks = readAllTracks(data, reader, header, name)
        val allNotes = tracks.flatMap { it.notes }.sortedBy { it.startTick }
        val allTempos = tracks.flatMap { it.tempoChanges }.distinct()
        val allTimeSignatures = tracks.flatMap { it.timeSignatures }.distinct()

        return MelodyTrack(
            name = name,
            notes = allNotes,
            tempoChanges = if (allTempos.isEmpty()) listOf(TempoEvent(0, 500_000)) else allTempos,
            timeSignatures = allTimeSignatures,
            ticksPerQuarterNote = header.timeDivision
        )
    }

    /**
     * Parse a MIDI file into one [MelodyTrack] per original MIDI track.
     * Useful for multi-track / multi-voice MIDI files where each track
     * should retain its identity (e.g. different instruments).
     */
    fun parseMultiTrack(inputStream: InputStream, name: String = "Unknown"): List<MelodyTrack> {
        val data = inputStream.readBytes()
        val reader = MidiTrackReader(data)
        val header = reader.readHeader()
        require(header.format in 0..1) { "Only MIDI format 0 and 1 are supported, got ${header.format}" }
        require(header.timeDivision > 0) { "Only ticks-per-quarter-note time division is supported" }
        return readAllTracks(data, reader, header, name)
    }

    private fun readAllTracks(
        data: ByteArray,
        reader: MidiTrackReader,
        header: MidiTrackReader.Header,
        name: String
    ): List<MelodyTrack> {
        var pos = reader.firstTrackPosition
        val tracks = mutableListOf<MelodyTrack>()

        for (trackIdx in 0 until header.numTracks) {
            if (pos + 8 > data.size) break
            require(readMidiString(data, pos, 4) == "MTrk") { "Expected MTrk at position $pos" }
            val trackLength = readMidiInt32(data, pos + 4)
            val trackEnd = pos + 8 + trackLength

            val notes = mutableListOf<NoteEvent>()
            val tempos = mutableListOf<TempoEvent>()
            val timeSigs = mutableListOf<TimeSignatureEvent>()

            val callback = object : MidiTrackReader.EventCallback {
                override fun onNoteEvent(note: NoteEvent) { notes.add(note) }
                override fun onTempoEvent(tick: Long, usPerBeat: Long) {
                    tempos.add(TempoEvent(tick, usPerBeat))
                }
                override fun onTimeSignature(tick: Long, numerator: Int, denominator: Int) {
                    timeSigs.add(TimeSignatureEvent(tick, numerator, denominator))
                }
                override fun onEndOfTrack(tick: Long, activeNotes: List<MidiTrackReader.ActiveNote>) {
                    for (an in activeNotes) {
                        notes.add(NoteEvent(an.note, an.velocity, an.startTick, tick - an.startTick))
                    }
                }
            }

            pos = reader.readTrack(pos, trackEnd, callback, TAG)
            notes.sortBy { it.startTick }

            val trackName = if (header.numTracks > 1) "$name - Track ${trackIdx + 1}" else name
            val mergedTempos = if (tempos.isEmpty()) listOf(TempoEvent(0, 500_000)) else tempos
            tracks.add(MelodyTrack(trackName, notes, mergedTempos, timeSigs, header.timeDivision))
        }
        return tracks
    }

    companion object {
        private const val TAG = "MidiParser"
    }
}
