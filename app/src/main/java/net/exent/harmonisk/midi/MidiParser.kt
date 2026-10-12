package net.exent.harmonisk.midi

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
            ticksPerQuarterNote = header.timeDivision,
            keySignature = tracks.firstNotNullOfOrNull { it.keySignature }
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

        // First pass: read all tracks to collect global tempo/time-sig/key events
        val allTrackData = mutableListOf<MutableList<NoteEvent>>()
        val allTempos = mutableListOf<TempoEvent>()
        val allTimeSigs = mutableListOf<TimeSignatureEvent>()
        val allKeys = mutableListOf<Pair<Long, KeySignature>>()

        for (trackIdx in 0 until header.numTracks) {
            if (pos + 8 > data.size) break
            require(readMidiString(data, pos, 4) == "MTrk") { "Expected MTrk at position $pos" }
            val trackLength = readMidiInt32(data, pos + 4)
            val trackEnd = pos + 8 + trackLength

            val notes = mutableListOf<NoteEvent>()

            val callback = object : MidiTrackReader.EventCallback {
                override fun onNoteEvent(note: NoteEvent) { notes.add(note) }
                override fun onTempoEvent(tick: Long, usPerBeat: Long) {
                    allTempos.add(TempoEvent(tick, usPerBeat))
                }
                override fun onTimeSignature(tick: Long, numerator: Int, denominator: Int) {
                    allTimeSigs.add(TimeSignatureEvent(tick, numerator, denominator))
                }
                override fun onKeySignature(tick: Long, fifths: Int, isMinor: Boolean) {
                    allKeys.add(tick to KeySignature(fifths, isMinor))
                }
                override fun onEndOfTrack(tick: Long, activeNotes: List<MidiTrackReader.ActiveNote>) {
                    for (an in activeNotes) {
                        notes.add(NoteEvent(an.note, an.velocity, an.startTick, tick - an.startTick))
                    }
                }
            }

            pos = reader.readTrack(pos, trackEnd, callback, TAG)
            notes.sortBy { it.startTick }
            allTrackData.add(notes)
        }

        val globalTempos = if (allTempos.isEmpty()) listOf(TempoEvent(0, 500_000)) else allTempos
        val notatedKey = allKeys.minByOrNull { it.first }?.second

        // Second pass: build MelodyTrack with shared global tempo/time-sig map
        return allTrackData.mapIndexed { trackIdx, notes ->
            val trackName = if (header.numTracks > 1) "$name - Track ${trackIdx + 1}" else name
            MelodyTrack(
                name = trackName,
                notes = notes,
                tempoChanges = globalTempos,
                timeSignatures = allTimeSigs,
                ticksPerQuarterNote = header.timeDivision,
                keySignature = notatedKey ?: KeyDetector.detect(notes)
            )
        }
    }

    companion object {
        private const val TAG = "MidiParser"
    }
}
