package net.exent.harmonisk.midi

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a MusicXML score (score-partwise) into one [MelodyTrack] per `<part>`, so each part
 * behaves like a MIDI track/voice. Note durations, chords, ties, tempo, time signatures and
 * the key signature are carried over; repeat barlines are unrolled. Voltas and multi-measure
 * tempo maps are not interpreted.
 */
object MusicXmlParser {

    /**
     * MusicXML measures durations in arbitrary divisions per quarter note, while the rest of
     * the app expects a realistic MIDI tick resolution; 480 divides every note value a score
     * can write.
     */
    private const val PPQ = 480

    private val STEP_PITCH_CLASS = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)

    fun parse(input: InputStream, name: String): List<MelodyTrack> {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            factory.newDocumentBuilder().apply {
                // MusicXML points at a DTD on the web; never let the parser go fetch it.
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(input)
        } catch (e: Exception) {
            throw java.io.IOException("Not a MusicXML document", e)
        }

        val root = document.documentElement ?: return emptyList()
        if (root.tagName != "score-partwise") return emptyList()

        val partNames = root.child("part-list")?.children("score-part")
            ?.associate { it.getAttribute("id") to it.childText("part-name") }
            ?: emptyMap()

        val tracks = mutableListOf<MelodyTrack>()
        root.children("part").forEachIndexed { index, part ->
            val partName = partNames[part.getAttribute("id")]?.takeIf { it.isNotBlank() }
                ?: "$name - Part ${index + 1}"
            parsePart(part, partName)?.let { tracks.add(it) }
        }
        return tracks
    }

    private fun parsePart(part: Element, trackName: String): MelodyTrack? {
        val measures = part.children("measure")
        if (measures.isEmpty()) return null

        val ppq = PPQ
        var divisions = firstDivisions(measures) ?: PPQ
        var pos = 0L
        var chordStart = 0L
        var key: KeySignature? = null
        val timeSignatures = mutableListOf<TimeSignatureEvent>()
        val tempos = mutableListOf<TempoEvent>()
        val notes = mutableListOf<NoteEvent>()
        val openTies = mutableMapOf<Int, Int>()

        fun ticksOf(duration: Int): Long = duration.toLong() * ppq / divisions

        for (measure in playbackOrder(measures)) {
            for (element in measure.children()) {
                when (element.tagName) {
                    "attributes" -> {
                        element.childText("divisions")?.toIntOrNull()?.takeIf { it > 0 }?.let { divisions = it }
                        element.child("key")?.let { keyElement ->
                            val fifths = keyElement.childText("fifths")?.toIntOrNull()
                            if (fifths != null) {
                                val minor = keyElement.childText("mode")?.equals("minor", true) == true
                                key = KeySignature(fifths.coerceIn(-7, 7), minor)
                            }
                        }
                        element.child("time")?.let { time ->
                            val beats = time.childText("beats")?.toIntOrNull()
                            val beatType = time.childText("beat-type")?.toIntOrNull()
                            if (beats != null && beatType != null && beats > 0 && beatType > 0) {
                                timeSignatures.add(TimeSignatureEvent(pos, beats, beatType))
                            }
                        }
                    }

                    "direction" -> {
                        element.child("sound")?.tempoFromSound()?.let { tempos.add(TempoEvent(pos, it)) }
                        element.descendants("metronome").firstOrNull()?.let { metronome ->
                            val perMinute = metronome.childText("per-minute")?.toIntOrNull()
                            if (perMinute != null && perMinute > 0) {
                                tempos.add(TempoEvent(pos, 60_000_000L / perMinute))
                            }
                        }
                    }

                    "sound" -> element.tempoFromSound()?.let { tempos.add(TempoEvent(pos, it)) }

                    "backup" -> pos = (pos - ticksOf(element.childText("duration")?.toIntOrNull() ?: 0)).coerceAtLeast(0)
                    "forward" -> pos += ticksOf(element.childText("duration")?.toIntOrNull() ?: 0)

                    "note" -> {
                        if (element.child("grace") != null) continue
                        val duration = ticksOf(element.childText("duration")?.toIntOrNull() ?: 0)
                        val isChord = element.child("chord") != null
                        val isRest = element.child("rest") != null
                        val start = if (isChord) chordStart else pos
                        if (!isChord) {
                            chordStart = start
                            pos += duration
                        }
                        if (isRest || duration <= 0) continue

                        val pitch = element.child("pitch")?.let { midiOf(it) } ?: continue
                        val tieStart = element.hasTie("start")
                        val tieStop = element.hasTie("stop")
                        val openIndex = openTies[pitch]
                        if (tieStop && openIndex != null) {
                            val existing = notes[openIndex]
                            notes[openIndex] = existing.copy(
                                durationTicks = start + duration - existing.startTick
                            )
                            if (!tieStart) openTies.remove(pitch)
                        } else {
                            notes.add(NoteEvent(pitch, DEFAULT_VELOCITY, start, duration))
                            if (tieStart) openTies[pitch] = notes.lastIndex
                        }
                    }
                }
            }
        }

        if (notes.isEmpty()) return null
        return MelodyTrack(
            name = trackName,
            notes = notes.sortedBy { it.startTick },
            tempoChanges = tempos,
            timeSignatures = timeSignatures,
            ticksPerQuarterNote = ppq,
            keySignature = key ?: KeyDetector.detect(notes)
        )
    }

    /** First `<divisions>` anywhere in the part, so positions can be read before the attribute appears. */
    private fun firstDivisions(measures: List<Element>): Int? =
        measures.asSequence()
            .flatMap { it.children("attributes").asSequence() }
            .mapNotNull { it.childText("divisions")?.toIntOrNull() }
            .firstOrNull { it > 0 }

    /**
     * The order measures are played in: repeat barlines (forward/backward with `times`) are
     * unrolled, everything else plays once in written order.
     */
    private fun playbackOrder(measures: List<Element>): List<Element> {
        val order = mutableListOf<Element>()
        val played = mutableMapOf<Int, Int>()
        var repeatStart = 0
        var index = 0
        val limit = measures.size * 8 + 100
        while (index < measures.size && order.size < limit) {
            val measure = measures[index]
            order.add(measure)
            if (hasRepeat(measure, "forward")) repeatStart = index
            val times = backwardRepeatTimes(measure)
            if (times != null && repeatStart <= index) {
                val done = played[index] ?: 1
                if (done < times) {
                    played[index] = done + 1
                    index = repeatStart
                    continue
                }
            }
            index++
        }
        return order
    }

    private fun hasRepeat(measure: Element, direction: String): Boolean =
        measure.children("barline").any { barline ->
            barline.children("repeat").any { it.getAttribute("direction").equals(direction, true) }
        }

    private fun backwardRepeatTimes(measure: Element): Int? =
        measure.children("barline")
            .flatMap { it.children("repeat") }
            .filter { it.getAttribute("direction").equals("backward", true) }
            .map { it.getAttribute("times").toIntOrNull()?.coerceIn(1, 16) ?: 2 }
            .firstOrNull()

    private fun Element.hasTie(kind: String): Boolean {
        val ties = children("tie") + children("notations").flatMap { it.children("tied") }
        return ties.any { it.getAttribute("type").equals(kind, true) }
    }

    private fun midiOf(pitch: Element): Int? {
        val step = pitch.childText("step")?.uppercase() ?: return null
        val pitchClass = STEP_PITCH_CLASS[step] ?: return null
        val octave = pitch.childText("octave")?.toIntOrNull() ?: return null
        val alter = pitch.childText("alter")?.toDoubleOrNull()?.toInt() ?: 0
        return ((octave + 1) * 12 + pitchClass + alter).coerceIn(0, 127)
    }

    /** `<sound tempo="…">` is in quarter notes per minute. */
    private fun Element.tempoFromSound(): Long? {
        val tempo = getAttribute("tempo").toDoubleOrNull() ?: return null
        return if (tempo > 0) (60_000_000.0 / tempo).toLong() else null
    }

    private const val DEFAULT_VELOCITY = 80

    private fun Element.children(): List<Element> {
        val nodes = childNodes
        val result = mutableListOf<Element>()
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { result.add(it) }
        return result
    }

    private fun Element.child(tag: String): Element? = children().firstOrNull { it.tagName == tag }

    private fun Element.children(tag: String): List<Element> = children().filter { it.tagName == tag }

    private fun Element.descendants(tag: String): List<Element> {
        val result = mutableListOf<Element>()
        for (child in children()) {
            if (child.tagName == tag) result.add(child)
            result.addAll(child.descendants(tag))
        }
        return result
    }

    private fun Element.childText(tag: String): String? = child(tag)?.textContent?.trim()
}
