package net.exent.harmonisk.midi

import java.io.InputStream

class MidiParser {

    fun parse(inputStream: InputStream, name: String = "Unknown"): MelodyTrack {
        val source = readSource(inputStream)
        val tracks = readAllTracks(source.data, source.reader, source.header, name)
        val allNotes = tracks.flatMap { it.notes }.sortedBy { it.startTick }
        val allTempos = tracks.flatMap { it.tempoChanges }.distinct()
        val allTimeSignatures = tracks.flatMap { it.timeSignatures }.distinct()

        return MelodyTrack(
            name = name,
            notes = allNotes,
            tempoChanges = if (allTempos.isEmpty()) listOf(TempoEvent(0, 500_000)) else allTempos,
            timeSignatures = allTimeSignatures,
            ticksPerQuarterNote = source.header.timeDivision,
            keySignature = tracks.firstNotNullOfOrNull { it.keySignature }
        )
    }

    fun parseMultiTrack(inputStream: InputStream, name: String = "Unknown"): List<MelodyTrack> {
        val source = readSource(inputStream)
        return readAllTracks(source.data, source.reader, source.header, name)
    }

    private data class MidiSource(
        val data: ByteArray,
        val reader: MidiTrackReader,
        val header: MidiTrackReader.Header
    )

    private fun readSource(inputStream: InputStream): MidiSource {
        val data = inputStream.readBytes()
        val reader = MidiTrackReader(data)
        val header = reader.readHeader()
        require(header.format in 0..1) { "Only MIDI format 0 and 1 are supported, got ${header.format}" }
        require(header.timeDivision > 0) { "Only ticks-per-quarter-note time division is supported" }
        return MidiSource(data, reader, header)
    }

    private fun readAllTracks(
        data: ByteArray,
        reader: MidiTrackReader,
        header: MidiTrackReader.Header,
        name: String
    ): List<MelodyTrack> {
        var pos = reader.firstTrackPosition

        val allTrackData = mutableListOf<MutableList<NoteEvent>>()
        val allTempos = mutableListOf<TempoEvent>()
        val allTimeSigs = mutableListOf<TimeSignatureEvent>()
        val allKeys = mutableListOf<Pair<Long, KeySignature>>()

        repeat(header.numTracks) {
            if (pos + 8 > data.size) return@repeat
            require(readMidiString(data, pos, 4) == "MTrk") { "Expected MTrk at position $pos" }
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
                    notes.addAll(activeNotes.toNoteEvents(tick))
                }
            }

            pos = reader.readTrack(pos, callback, TAG)
            notes.sortBy { it.startTick }
            allTrackData.add(notes)
        }

        val globalTempos = if (allTempos.isEmpty()) listOf(TempoEvent(0, 500_000)) else allTempos
        val notatedKey = allKeys.minByOrNull { it.first }?.second

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
