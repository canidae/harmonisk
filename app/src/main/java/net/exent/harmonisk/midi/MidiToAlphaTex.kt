package net.exent.harmonisk.midi

import kotlin.math.roundToInt

/**
 * Converts a [MelodyTrack] into alphaTex notation for sheet music rendering with
 * alphaTab. Note timings are quantized to a sixteenth-note grid, arbitrary
 * durations are decomposed into standard note values joined by ties, and
 * simultaneous notes are rendered as chords.
 *
 * Exporters such as MuseScore cut notes short by around 5% (gate time), which
 * leaves a gap that is far smaller than the grid. Quantizing such a note ends it
 * one grid early, so it renders as a dotted note tied to a sliver. Notes are
 * therefore held until the next onset when the gap between them is smaller than
 * one grid step, which is what the gap is too small to notate anyway.
 */
object MidiToAlphaTex {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NOTE_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** alphaTab's key signature names by fifths, -7..7; minor keys carry the "minor" suffix. */
    private val MAJOR_KEY_NAMES = arrayOf(
        "cb", "gb", "db", "ab", "eb", "bb", "f", "c", "g", "d", "a", "e", "b", "f#", "c#"
    )
    private val MINOR_KEY_NAMES = arrayOf(
        "abminor", "ebminor", "bbminor", "fminor", "cminor", "gminor", "dminor",
        "aminor", "eminor", "bminor", "f#minor", "c#minor", "g#minor", "d#minor", "a#minor"
    )

    /** Standard durations in sixteenth-note units; [align] restricts where a piece may start (bar-relative grid position). */
    private data class DurationPiece(val gridLength: Long, val value: Int, val dots: Int, val align: Long)

    private val DURATION_PIECES = listOf(
        DurationPiece(16, 1, 0, 16),
        DurationPiece(14, 2, 2, 8),
        DurationPiece(12, 2, 1, 8),
        DurationPiece(8, 2, 0, 8),
        DurationPiece(7, 4, 2, 4),
        DurationPiece(6, 4, 1, 4),
        DurationPiece(4, 4, 0, 4),
        DurationPiece(3, 8, 1, 2),
        DurationPiece(2, 8, 0, 2),
        DurationPiece(1, 16, 0, 1)
    )

    /**
     * Gaps shorter than half a grid step are cut-short notes; anything longer is a rest that
     * can be written, and filling it would change the music. Exporter gate times come to
     * well under this: a quarter note exported at 470 ticks leaves an eighth of a step.
     */
    private const val GAP_MERGE_LIMIT_GRID = 0.5

    /** Grid steps in one beat; four grid steps make a quarter note. */
    private const val BEAT_GRID = 4L

    /** Note with start/end quantized to grid units (one grid unit = a sixteenth note). */
    private data class QNote(val noteNumber: Int, val start: Long, val end: Long)

    /** A note within a chord together with its tie state. */
    private data class ChordNote(val name: String, val tied: Boolean)

    private data class Bar(
        val startTick: Long,
        val lengthTicks: Long,
        val startGrid: Long,
        val lengthGrid: Long,
        val timeSignature: TimeSignatureEvent
    )

    fun noteName(midiNumber: Int, flats: Boolean = false): String {
        val index = ((midiNumber % 12) + 12) % 12
        val octave = midiNumber / 12 - 1
        return "${if (flats) FLAT_NOTE_NAMES[index] else NOTE_NAMES[index]}$octave"
    }

    /** The key's alphaTab name, e.g. "d" for D major or "f#minor". */
    private fun keyName(key: KeySignature): String {
        val names = if (key.isMinor) MINOR_KEY_NAMES else MAJOR_KEY_NAMES
        return names[key.fifths.coerceIn(-7, 7) + 7]
    }

    fun generate(track: MelodyTrack): String {
        val ppq = track.ticksPerQuarterNote.coerceAtLeast(4)
        val grid = (ppq / 4).toLong()
        fun tickToGrid(tick: Long) = (tick + grid / 2) / grid

        val key = track.keySignature
        val flats = key?.usesFlats == true
        val notes = normalizeGaps(quantizeNotes(track, grid, ::tickToGrid))
        val bars = buildBars(track, ppq, ::tickToGrid, notes.maxOfOrNull { it.end } ?: 0L)

        val tokens = mutableListOf<String>()
        // Score-level metadata must be terminated by a dot before the first bar (alphaTex < 1.7 format)
        tokens.add("\\tempo ${track.bpmAt(0).roundToInt()}")
        // Declare a piano instrument so alphaTab does not default to a guitar
        // program, which would apply a -12 display transposition and render
        // every note one octave higher.
        tokens.add("\\instrument piano")
        tokens.add(".")
        val ts0 = track.timeSignatureAt(0)
        tokens.add("\\ts ${ts0.numerator} ${ts0.denominator}")
        key?.let { tokens.add("\\ks ${keyName(it)}") }
        val maxNote = track.notes.maxOfOrNull { it.noteNumber }
        if (maxNote != null && maxNote < 60) tokens.add("\\clef bass")

        val tempoChanges = track.tempoChanges.filter { it.tick > 0 }.sortedBy { it.tick }
        var noteIndex = 0
        bars.forEachIndexed { barIndex, bar ->
            if (barIndex > 0) {
                val prevTs = bars[barIndex - 1].timeSignature
                val ts = bar.timeSignature
                if (ts.numerator != prevTs.numerator || ts.denominator != prevTs.denominator) {
                    tokens.add("\\ts ${ts.numerator} ${ts.denominator}")
                }
            }
            val barEndTick = bar.startTick + bar.lengthTicks
            val midBarTempos = mutableMapOf<Long, Int>()
            for (tempo in tempoChanges) {
                if (barIndex > 0 && tempo.tick == bar.startTick) {
                    // Tempo change at a bar boundary: bar metadata
                    tokens.add("\\tempo ${tempo.bpm.roundToInt()}")
                } else if (tempo.tick in (bar.startTick + 1) until barEndTick) {
                    // Mid-bar tempo change: beat effect at the (quantized) change position
                    midBarTempos[tickToGrid(tempo.tick)] = tempo.bpm.roundToInt()
                }
            }

            // Emit the bar's beats
            var pos = bar.startGrid
            val barEndGrid = bar.startGrid + bar.lengthGrid
            while (pos < barEndGrid) {
                while (noteIndex < notes.size && notes[noteIndex].end <= pos) noteIndex++
                val active = mutableListOf<QNote>()
                var scan = noteIndex
                while (scan < notes.size && notes[scan].start <= pos) {
                    if (notes[scan].end > pos) active.add(notes[scan])
                    scan++
                }
                val beatProps = midBarTempos[pos]?.let { "{tempo $it}" } ?: ""
                val nextTempoBoundary = midBarTempos.keys.filter { it > pos }.minOrNull() ?: Long.MAX_VALUE
                val nextStart = if (scan < notes.size) notes[scan].start else Long.MAX_VALUE
                val minEnd = active.minOfOrNull { it.end } ?: Long.MAX_VALUE
                val segEnd = minOf(barEndGrid, nextStart, minEnd, nextTempoBoundary)
                val segLength = segEnd - pos
                if (segLength <= 0) break
                if (active.isNotEmpty()) {
                    val chordNotes = active
                        .sortedBy { it.noteNumber }
                        .map { ChordNote(noteName(it.noteNumber, flats), tied = pos > it.start) }
                    tokens.addAll(chordTokens(chordNotes, pos - bar.startGrid, segLength, firstPieceBeatProps = beatProps))
                } else {
                    tokens.addAll(restTokens(segLength, firstPieceBeatProps = beatProps))
                }
                pos = segEnd
            }
            tokens.add("|")
        }
        return tokens.joinToString(" ")
    }

    private fun quantizeNotes(track: MelodyTrack, grid: Long, tickToGrid: (Long) -> Long): List<QNote> {
        // Both ends are rounded against the grid itself rather than the duration being rounded,
        // so a note that starts a little off the grid still reaches its own grid position.
        fun tickToGridAbsolute(tick: Long) = (tick + grid / 2) / grid
        val quantized = track.notes
            .sortedBy { it.startTick }
            .filter { it.endTick > 0 }
            .mapNotNull { note ->
                val start = tickToGrid(note.startTick.coerceAtLeast(0))
                val end = maxOf(start + 1, tickToGridAbsolute(note.endTick))
                if (end > start) QNote(note.noteNumber, start, end) else null
            }
        // Merge same-pitch overlapping notes so a pitch appears at most once in a chord
        val merged = mutableListOf<QNote>()
        for (note in quantized) {
            val last = merged.lastOrNull()
            if (last != null && last.noteNumber == note.noteNumber && note.start < last.end) {
                merged[merged.lastIndex] = last.copy(end = maxOf(last.end, note.end))
            } else {
                merged.add(note)
            }
        }
        return merged
    }

    /**
     * Holds a note until the next onset when the gap between them is smaller than one grid
     * step. Such a gap is too small to notate: leaving it turns a plain note into a dotted
     * note tied to a sliver. A whole grid step is left alone, because a rest that can be
     * written must not be filled in.
     */
    private fun normalizeGaps(notes: List<QNote>): List<QNote> {
        if (notes.isEmpty()) return notes
        val startsAfter = notes.map { note -> notes.filter { it.start >= note.end }.minOfOrNull { it.start } }
        return notes.mapIndexed { index, note ->
            val next = startsAfter[index]
            if (next != null && next - note.end < GAP_MERGE_LIMIT_GRID) note.copy(end = next) else note
        }
    }

    private fun buildBars(
        track: MelodyTrack,
        ppq: Int,
        tickToGrid: (Long) -> Long,
        endGrid: Long
    ): List<Bar> {
        val bars = mutableListOf<Bar>()
        var barStartTick = 0L
        while (true) {
            val ts = track.timeSignatureAt(barStartTick)
            var barTicks = (ppq * 4L * ts.numerator) / ts.denominator
            if (barTicks <= 0) barTicks = ppq * 4L
            // A time signature change inside this bar ends it early
            val nextTs = track.timeSignatures.firstOrNull { it.tick > barStartTick }
            if (nextTs != null && nextTs.tick < barStartTick + barTicks) {
                barTicks = nextTs.tick - barStartTick
            }
            bars.add(
                Bar(
                    startTick = barStartTick,
                    lengthTicks = barTicks,
                    startGrid = tickToGrid(barStartTick),
                    lengthGrid = tickToGrid(barStartTick + barTicks) - tickToGrid(barStartTick),
                    timeSignature = ts
                )
            )
            barStartTick += barTicks
            if (tickToGrid(barStartTick) >= endGrid || bars.size >= 10_000) break
        }
        return bars
    }

    /**
     * Decomposes [gridLength] into tied chord pieces, respecting beat alignment.
     * All notes in the chord share the same duration; a note carries a tie marker
     * when it continues from a previous segment.
     */
    private fun chordTokens(
        notes: List<ChordNote>,
        startGridInBar: Long,
        gridLength: Long,
        firstPieceBeatProps: String = ""
    ): List<String> {
        val tokens = mutableListOf<String>()
        var remaining = gridLength
        var pos = startGridInBar
        var first = true
        while (remaining > 0) {
            val piece = pickPiece(remaining, pos)
            val body = if (notes.size == 1) {
                val note = notes[0]
                "${note.name}${if (first && note.tied || !first) "{t}" else ""}"
            } else {
                "(" + notes.joinToString(" ") { note ->
                    "${note.name}${if (first && note.tied || !first) "{t}" else ""}"
                } + ")"
            }
            val beatProps = if (first) firstPieceBeatProps else ""
            tokens.add("$body.${piece.value}${dotsSuffix(piece.dots)}$beatProps")
            remaining -= piece.gridLength
            pos += piece.gridLength
            first = false
        }
        return tokens
    }

    /**
     * Largest piece that fits and is aligned, but one that uses up whole beats is preferred
     * over a longer one that eats into the next beat. Without that preference a note of two
     * beats is written as a double-dotted note tied to a thirty-second instead of two quarters.
     */
    private fun pickPiece(remaining: Long, pos: Long): DurationPiece {
        val candidates = DURATION_PIECES.filter { it.gridLength <= remaining && pos % it.align == 0L }
        val (endsOnBeat, rest) = candidates.partition { (pos + it.gridLength) % BEAT_GRID == 0L }
        return endsOnBeat.firstOrNull() ?: rest.first()
    }

    /** Decomposes [gridLength] into rest pieces (largest first, no alignment needed). */
    private fun restTokens(gridLength: Long, firstPieceBeatProps: String = ""): List<String> {
        val tokens = mutableListOf<String>()
        var remaining = gridLength
        var first = true
        while (remaining > 0) {
            val piece = DURATION_PIECES.first { it.gridLength <= remaining }
            val beatProps = if (first) firstPieceBeatProps else ""
            tokens.add("r.${piece.value}${dotsSuffix(piece.dots)}$beatProps")
            remaining -= piece.gridLength
            first = false
        }
        return tokens
    }

    private fun dotsSuffix(dots: Int): String = when (dots) {
        1 -> "{d}"
        2 -> "{dd}"
        else -> ""
    }
}
