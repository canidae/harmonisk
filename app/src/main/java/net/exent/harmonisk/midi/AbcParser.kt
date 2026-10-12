package net.exent.harmonisk.midi

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Reads ABC notation into one [MelodyTrack] per voice. Headers (T/M/L/Q/K/V) are honoured,
 * as are notes, rests, octave markers, accidentals (including their persistence within a
 * bar), chords, ties, tuplets, broken rhythm, repeats and first/second endings. Grace notes,
 * decorations, slurs, chord symbols and annotations are ignored; the tempo, meter and key
 * are read from the headers only.
 */
object AbcParser {

    /** Tick resolution of the generated tracks. */
    private const val PPQ = 480

    private const val DEFAULT_VELOCITY = 80

    private val STEP_PITCH_CLASS = mapOf(
        'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11
    )

    fun parse(text: String, fallbackName: String): List<MelodyTrack> {
        val tunes = splitTunes(cleanLines(text))
        val tracks = mutableListOf<MelodyTrack>()
        for (tune in tunes) tracks.addAll(parseTune(tune, fallbackName))
        return tracks
    }

    // ── Lines and tunes ────────────────────────────────────────────────────

    private fun cleanLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        val pending = StringBuilder()
        for (raw in text.lines()) {
            val line = stripComment(raw).trim()
            if (line.endsWith("\\")) {
                pending.append(line.dropLast(1))
                continue
            }
            val joined = pending.toString() + line
            pending.clear()
            if (joined.isNotBlank()) lines.add(joined)
        }
        if (pending.isNotBlank()) lines.add(pending.toString())
        return lines
    }

    private fun stripComment(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                '%' -> if (!inQuotes) return line.substring(0, i)
            }
        }
        return line
    }

    private fun splitTunes(lines: List<String>): List<List<String>> {
        val tunes = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (isHeader(line, 'X') && current.isNotEmpty()) {
                tunes.add(current)
                current = mutableListOf()
            }
            current.add(line)
        }
        if (current.isNotEmpty()) tunes.add(current)
        return tunes
    }

    // ── Tune parsing ───────────────────────────────────────────────────────

    private class Voice(val id: String) {
        val elements = mutableListOf<Element>()
        var unitLength = 1.0 / 8
        var meterNumerator = 4
        var meterDenominator = 4
        var microsecondsPerBeat: Long? = null
        var key: KeySignature? = null
    }

    private fun parseTune(lines: List<String>, fallbackName: String): List<MelodyTrack> {
        val voices = LinkedHashMap<String, Voice>()
        var currentVoiceId = "1"
        var title = ""
        var unitLength: Double? = null
        var meter = 4 to 4
        var microsecondsPerBeat: Long? = null
        var key: KeySignature? = null

        for (line in lines) {
            val field = headerField(line)
            if (field == null) {
                val voice = voices.getOrPut(currentVoiceId) { Voice(currentVoiceId) }.apply {
                    this.unitLength = unitLength ?: defaultUnitLength(meter)
                    meterNumerator = meter.first
                    meterDenominator = meter.second
                    this.microsecondsPerBeat = microsecondsPerBeat
                    this.key = key
                }
                voice.elements.addAll(parseBody(line, voice.unitLength, voice.key))
                continue
            }
            val value = line.substring(2).trim()
            when (field) {
                'T' -> if (title.isEmpty()) title = value
                'M' -> meter = parseMeter(value) ?: meter
                'L' -> parseFraction(value)?.let { unitLength = it }
                'Q' -> microsecondsPerBeat = parseTempo(value, unitLength ?: defaultUnitLength(meter)) ?: microsecondsPerBeat
                'K' -> parseKey(value)?.let { key = it }
                'V' -> currentVoiceId = value.substringBefore(' ').ifBlank { "1" }
            }
        }

        val name = title.ifBlank { fallbackName }
        return voices.values.mapNotNull { voice ->
            val trackName = if (voices.size > 1) "$name (${voice.id})" else name
            buildTrack(voice, trackName)
        }
    }

    private fun buildTrack(voice: Voice, name: String): MelodyTrack? {
        val beats = unroll(voice.elements)
        if (beats.none { it.notes.isNotEmpty() }) return null

        val notes = mutableListOf<NoteEvent>()
        val openIndices = mutableMapOf<Int, Int>()
        var carrying = false
        var pos = 0L
        for (beat in beats) {
            val ticks = (beat.duration * 4 * PPQ).roundToLong().coerceAtLeast(1)
            if (beat.notes.isEmpty()) {
                carrying = false
                openIndices.clear()
                pos += ticks
                continue
            }
            for (pitch in beat.notes) {
                val open = if (carrying) openIndices[pitch] else null
                if (open != null) {
                    val existing = notes[open]
                    notes[open] = existing.copy(durationTicks = pos + ticks - existing.startTick)
                } else {
                    notes.add(NoteEvent(pitch, DEFAULT_VELOCITY, pos, ticks))
                    openIndices[pitch] = notes.lastIndex
                }
            }
            pos += ticks
            if (beat.tie) {
                carrying = true
                openIndices.keys.retainAll(beat.notes.toSet())
            } else {
                carrying = false
                openIndices.clear()
            }
        }
        if (notes.isEmpty()) return null

        return MelodyTrack(
            name = name,
            notes = notes.sortedBy { it.startTick },
            tempoChanges = voice.microsecondsPerBeat?.let { listOf(TempoEvent(0, it)) } ?: emptyList(),
            timeSignatures = listOf(TimeSignatureEvent(0, voice.meterNumerator, voice.meterDenominator)),
            ticksPerQuarterNote = PPQ,
            keySignature = voice.key ?: KeyDetector.detect(notes)
        )
    }

    // ── Headers ────────────────────────────────────────────────────────────

    private fun headerField(line: String): Char? {
        if (line.length < 2 || line[1] != ':') return null
        val field = line[0].uppercaseChar()
        return if (field in "ABCDEFGHIKLMNOPQRSTUVWXZ") field else null
    }

    private fun isHeader(line: String, field: Char): Boolean = headerField(line) == field

    private fun defaultUnitLength(meter: Pair<Int, Int>): Double =
        if (meter.first.toDouble() / meter.second >= 0.75) 1.0 / 8 else 1.0 / 16

    private fun parseFraction(value: String): Double? {
        val match = Regex("""^(\d+)\s*/\s*(\d+)""").find(value.trim()) ?: return null
        val numerator = match.groupValues[1].toDouble()
        val denominator = match.groupValues[2].toDouble()
        return if (denominator > 0) numerator / denominator else null
    }

    private fun parseMeter(value: String): Pair<Int, Int>? {
        return when (value.trim()) {
            "C" -> 4 to 4
            "C|" -> 2 to 2
            else -> {
                val match = Regex("""^(\d+)\s*/\s*(\d+)""").find(value.trim()) ?: return null
                val numerator = match.groupValues[1].toInt()
                val denominator = match.groupValues[2].toInt()
                if (numerator > 0 && denominator > 0) numerator to denominator else null
            }
        }
    }

    /** `Q:1/4=120` is 120 of the given note value per minute; also accepts a bare `Q:120`. */
    private fun parseTempo(value: String, unitLength: Double): Long? {
        val cleaned = value.replace(Regex("\"[^\"]*\""), "").trim()
        val equals = cleaned.indexOf('=')
        if (equals < 0) {
            val bpm = cleaned.toDoubleOrNull() ?: return null
            return if (bpm > 0) (60_000_000.0 / bpm).toLong() else null
        }
        val bpm = cleaned.substring(equals + 1).trim().toDoubleOrNull() ?: return null
        val unit = parseFraction(cleaned.substring(0, equals)) ?: unitLength
        val quarterNotesPerUnit = unit * 4
        val quarterBpm = bpm * quarterNotesPerUnit
        return if (quarterBpm > 0) (60_000_000.0 / quarterBpm).toLong() else null
    }

    private fun parseKey(value: String): KeySignature? {
        val token = value.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val letter = token[0].uppercaseChar()
        val pitchClass = STEP_PITCH_CLASS[letter] ?: return null
        var accidental = 0
        var index = 1
        while (index < token.length && (token[index] == '#' || token[index] == 'b')) {
            accidental += if (token[index] == '#') 1 else -1
            index++
        }
        val suffix = token.substring(index).lowercase()
        val modeWords = value.lowercase()
        val isMinor = (suffix.startsWith("m") && !suffix.startsWith("maj")) || modeWords.contains("minor") || modeWords.contains("min ")
        return KeySignature.of(pitchClass + accidental, isMinor)
    }

    // ── Body lexing ────────────────────────────────────────────────────────

    private sealed interface Element

    private class BeatElement(val notes: List<Int>, var duration: Double, var tie: Boolean = false) : Element

    private class BarElement(val repeatStart: Boolean, val repeatEnd: Boolean, val ending: Int?) : Element

    private fun parseBody(body: String, unitLength: Double, key: KeySignature?): List<Element> {
        val elements = mutableListOf<Element>()
        val barAlterations = mutableMapOf<Int, Int>()
        var pendingFactor = 1.0
        var tupletRemaining = 0
        var tupletFactor = 1.0
        var i = 0

        fun addBeat(notes: List<Int>, baseDuration: Double) {
            var duration = baseDuration * pendingFactor
            if (tupletRemaining > 0) {
                duration *= tupletFactor
                tupletRemaining--
            }
            pendingFactor = 1.0
            elements.add(BeatElement(notes, duration))
        }

        while (i < body.length) {
            val c = body[i]
            when {
                c.isWhitespace() -> i++

                c == '"' -> i = skipTo(body, i + 1, '"')
                c == '!' -> i = skipTo(body, i + 1, '!')
                c == '+' -> i = skipTo(body, i + 1, '+')
                c == '{' -> i = skipTo(body, i + 1, '}')
                c == '~' || c == '.' || c == ')' -> i++

                c == '(' -> {
                    val digit = body.getOrNull(i + 1)
                    if (digit != null && digit.isDigit()) {
                        val (count, factor) = tuplet(digit - '0')
                        tupletRemaining = count
                        tupletFactor = factor
                        i += 2
                    } else {
                        i++
                    }
                }

                c == '[' -> {
                    val next = body.getOrNull(i + 1)
                    when {
                        next != null && (next.isDigit() || next == ',') -> {
                            val (ending, nextIndex) = parseEnding(body, i)
                            elements.add(BarElement(false, false, ending))
                            i = nextIndex
                        }
                        next == '|' -> {
                            elements.add(BarElement(false, false, null))
                            i += 2
                        }
                        isInlineField(body, i) -> i = skipTo(body, i + 1, ']')
                        else -> {
                            val (notes, nextIndex) = parseChord(body, i, key, barAlterations)
                            i = nextIndex
                            val (duration, after) = parseDuration(body, i, unitLength)
                            addBeat(notes, duration)
                            i = after
                        }
                    }
                }

                c == '|' || c == ':' || c == ']' -> {
                    val (bar, nextIndex) = parseBar(body, i)
                    elements.add(bar)
                    barAlterations.clear()
                    i = nextIndex
                }

                c == '-' -> {
                    (elements.lastOrNull() as? BeatElement)?.tie = true
                    i++
                }

                c == '>' || c == '<' -> {
                    var count = 0
                    while (i < body.length && body[i] == c) { count++; i++ }
                    val long = (2.0.pow(count + 1) - 1) / 2.0.pow(count)
                    val short = 1.0 / 2.0.pow(count)
                    (elements.lastOrNull() as? BeatElement)?.let { it.duration *= if (c == '>') long else short }
                    pendingFactor = if (c == '>') short else long
                }

                c == 'z' || c == 'x' -> {
                    val (duration, after) = parseDuration(body, i + 1, unitLength)
                    addBeat(emptyList(), duration)
                    i = after
                }

                c in "^_=ABCDEFGabcdefg" -> {
                    val note = parseNote(body, i, key, barAlterations)
                    if (note == null) {
                        i++
                    } else {
                        val (duration, after) = parseDuration(body, note.second, unitLength)
                        addBeat(listOf(note.first), duration)
                        i = after
                    }
                }

                else -> i++
            }
        }
        return elements
    }

    private fun tuplet(digit: Int): Pair<Int, Double> = when (digit) {
        2 -> 2 to 3.0 / 2
        3 -> 3 to 2.0 / 3
        4 -> 4 to 3.0 / 4
        6 -> 6 to 2.0 / 6
        8 -> 8 to 3.0 / 8
        else -> digit.coerceAtLeast(1) to 1.0
    }

    private fun parseEnding(body: String, start: Int): Pair<Int, Int> {
        var i = start + 1
        val numbers = mutableListOf<Int>()
        val current = StringBuilder()
        while (i < body.length && body[i] != ']') {
            val c = body[i]
            when {
                c.isDigit() -> current.append(c)
                c == ',' || c == '-' -> {
                    current.toString().toIntOrNull()?.let { numbers.add(it) }
                    current.clear()
                }
                c == ' ' -> {}
                else -> break
            }
            i++
        }
        current.toString().toIntOrNull()?.let { numbers.add(it) }
        if (body.getOrNull(i) == ']') i++
        return (numbers.firstOrNull() ?: 1) to i
    }

    private fun isInlineField(body: String, start: Int): Boolean {
        val field = body.getOrNull(start + 1) ?: return false
        return body.getOrNull(start + 2) == ':' && field.uppercaseChar() in "KMLQ"
    }

    private fun parseBar(body: String, start: Int): Pair<BarElement, Int> {
        var i = start
        val run = StringBuilder()
        while (i < body.length && body[i] in "|:]") {
            run.append(body[i])
            i++
        }
        val value = run.toString()
        val repeatStart = value.contains("|:") || value.contains("::") || value.contains(":|:")
        val repeatEnd = value.contains(":|") || value.contains("::")
        // A volta number may follow the bar line directly ("|1", "|2", ":|2").
        val ending = if (i < body.length && body[i].isDigit()) {
            val numbers = StringBuilder()
            while (i < body.length && (body[i].isDigit() || body[i] == ',')) {
                numbers.append(body[i])
                i++
            }
            numbers.toString().split(',').firstNotNullOfOrNull { it.toIntOrNull() }
        } else {
            null
        }
        return BarElement(repeatStart, repeatEnd, ending) to i
    }

    private fun parseChord(
        body: String,
        start: Int,
        key: KeySignature?,
        barAlterations: MutableMap<Int, Int>
    ): Pair<List<Int>, Int> {
        val notes = mutableListOf<Int>()
        var i = start + 1
        while (i < body.length && body[i] != ']') {
            val c = body[i]
            if (c in "^_=ABCDEFGabcdefg") {
                val (pitch, next) = parseNote(body, i, key, barAlterations) ?: break
                notes.add(pitch)
                i = next
            } else {
                i++
            }
        }
        if (body.getOrNull(i) == ']') i++
        return notes to i
    }

    private fun parseNote(
        body: String,
        start: Int,
        key: KeySignature?,
        barAlterations: MutableMap<Int, Int>
    ): Pair<Int, Int>? {
        var i = start
        var alteration = 0
        var accidentalGiven = false
        while (i < body.length && body[i] in "^_=") {
            when (body[i]) {
                '^' -> { alteration += 1; accidentalGiven = true }
                '_' -> { alteration -= 1; accidentalGiven = true }
                '=' -> { alteration = 0; accidentalGiven = true }
            }
            i++
        }
        val letter = body.getOrNull(i) ?: return null
        val pitchClass = STEP_PITCH_CLASS[letter.uppercaseChar()] ?: return null
        var midi = 60 + pitchClass
        if (letter.isLowerCase()) midi += 12
        i++
        while (i < body.length && (body[i] == ',' || body[i] == '\'')) {
            midi += if (body[i] == ',') -12 else 12
            i++
        }
        val pc = ((midi % 12) + 12) % 12
        if (accidentalGiven) {
            barAlterations[pc] = alteration
        } else {
            alteration = barAlterations[pc] ?: KeySignature.keyAlteration(pc, key?.fifths ?: 0)
        }
        return (midi + alteration).coerceIn(0, 127) to i
    }

    /** Duration of a note/rest, in whole-note fractions, after the note has been read. */
    private fun parseDuration(body: String, start: Int, unitLength: Double): Pair<Double, Int> {
        var i = start
        val numerator = StringBuilder()
        val denominator = StringBuilder()
        var slashes = 0
        while (i < body.length && body[i].isDigit()) { numerator.append(body[i]); i++ }
        while (i < body.length && body[i] == '/') {
            slashes++
            i++
            while (i < body.length && body[i].isDigit()) { denominator.append(body[i]); i++ }
        }
        val num = numerator.toString().toDoubleOrNull() ?: 1.0
        val den = when {
            denominator.isNotEmpty() -> denominator.toString().toDouble()
            slashes > 0 -> 2.0.pow(slashes)
            else -> 1.0
        }
        return unitLength * num / den to i
    }

    private fun skipTo(body: String, start: Int, terminator: Char): Int {
        val end = body.indexOf(terminator, start)
        return if (end < 0) body.length else end + 1
    }

    // ── Repeats and endings ────────────────────────────────────────────────

    private fun unroll(elements: List<Element>): List<BeatElement> {
        val out = mutableListOf<BeatElement>()
        var i = 0
        while (i < elements.size) {
            val element = elements[i]
            if (element is BarElement && element.repeatStart) {
                val repeatEnd = (i + 1 until elements.size).firstOrNull { isRepeatEnd(elements[it]) }
                if (repeatEnd != null) {
                    val ending1 = (i + 1..repeatEnd).firstOrNull { endingNumber(elements[it]) == 1 }
                    val bodyEnd = (ending1 ?: repeatEnd) - 1
                    val body = beatRange(elements, i + 1, bodyEnd)
                    out.addAll(body)
                    if (ending1 != null) {
                        out.addAll(beatRange(elements, ending1, repeatEnd))
                        out.addAll(body)
                        val ending2 = (repeatEnd + 1 until elements.size).firstOrNull { endingNumber(elements[it]) == 2 }
                        if (ending2 != null) {
                            val ending2End = endingEnd(elements, ending2, repeatEnd)
                            out.addAll(beatRange(elements, ending2, ending2End))
                            i = ending2End + 1
                        } else {
                            i = repeatEnd + 1
                        }
                    } else {
                        out.addAll(body)
                        i = repeatEnd + 1
                    }
                    continue
                }
            }
            if (element is BeatElement) out.add(element)
            i++
        }
        return out
    }

    private fun isRepeatEnd(element: Element): Boolean = element is BarElement && element.repeatEnd

    private fun endingNumber(element: Element): Int? = (element as? BarElement)?.ending

    private fun beatRange(elements: List<Element>, from: Int, to: Int): List<BeatElement> {
        if (from > to) return emptyList()
        return (from..to).mapNotNull { elements[it] as? BeatElement }
    }

    /** The bar that closes the ending starting at [start]; endings inside a repeat end at the repeat. */
    private fun endingEnd(elements: List<Element>, start: Int, repeatEnd: Int): Int {
        if (start < repeatEnd) return repeatEnd
        var seenBeat = false
        var i = start + 1
        while (i < elements.size) {
            val element = elements[i]
            if (element is BeatElement) seenBeat = true
            if (element is BarElement && (element.repeatEnd || element.repeatStart || seenBeat)) return i
            i++
        }
        return elements.size - 1
    }
}
