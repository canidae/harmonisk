package net.exent.harmonisk.midi

import java.io.ByteArrayInputStream
import java.io.InputStream

enum class MelodyFormat(val extension: String) {
    MIDI("mid"),
    MUSIC_XML("musicxml"),
    ABC("abc")
}

object MelodyFileFormat {

    private const val SNIFF_BYTES = 1024

    fun detect(data: ByteArray): MelodyFormat {
        val head = String(data, 0, minOf(data.size, SNIFF_BYTES), Charsets.ISO_8859_1)
        val trimmed = head.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            trimmed.startsWith("MThd") -> MelodyFormat.MIDI
            trimmed.startsWith("<") -> MelodyFormat.MUSIC_XML
            else -> MelodyFormat.ABC
        }
    }
}

object MelodyFileParser {

    fun parse(input: InputStream, name: String): MelodyTrack? {
        val data = input.readBytes()
        return try {
            when (MelodyFileFormat.detect(data)) {
                MelodyFormat.MIDI -> MidiParser().parse(ByteArrayInputStream(data), name)
                MelodyFormat.MUSIC_XML -> merge(MusicXmlParser.parse(ByteArrayInputStream(data), name), name)
                MelodyFormat.ABC -> merge(AbcParser.parse(String(data, Charsets.UTF_8), name), name)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseMultiTrack(input: InputStream, name: String): List<MelodyTrack> {
        val data = input.readBytes()
        return try {
            when (MelodyFileFormat.detect(data)) {
                MelodyFormat.MIDI -> MidiParser().parseMultiTrack(ByteArrayInputStream(data), name)
                MelodyFormat.MUSIC_XML -> MusicXmlParser.parse(ByteArrayInputStream(data), name)
                MelodyFormat.ABC -> AbcParser.parse(String(data, Charsets.UTF_8), name)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun merge(tracks: List<MelodyTrack>, name: String): MelodyTrack? {
        if (tracks.isEmpty()) return null
        if (tracks.size == 1) return tracks[0]
        return MelodyTrack(
            name = name,
            notes = tracks.flatMap { it.notes }.sortedBy { it.startTick },
            tempoChanges = tracks.flatMap { it.tempoChanges }.distinct(),
            timeSignatures = tracks.flatMap { it.timeSignatures }.distinct(),
            ticksPerQuarterNote = tracks.first().ticksPerQuarterNote,
            keySignature = tracks.firstNotNullOfOrNull { it.keySignature }
        )
    }
}
