package net.exent.perfectpitch.midi

import kotlin.math.roundToInt

/**
 * Converts a (monophonic) [MelodyTrack] into alphaTex notation for sheet music
 * rendering with alphaTab. Note timings are quantized to a sixteenth-note grid
 * and arbitrary durations are decomposed into standard note values joined by ties.
 */
object MidiToAlphaTex {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

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

    /** Note with start/end quantized to grid units (one grid unit = a sixteenth note). */
    private data class QNote(val noteNumber: Int, val start: Long, val end: Long)

    private data class Bar(
        val startTick: Long,
        val lengthTicks: Long,
        val startGrid: Long,
        val lengthGrid: Long,
        val timeSignature: TimeSignatureEvent
    )

    fun noteName(midiNumber: Int): String {
        val index = ((midiNumber % 12) + 12) % 12
        val octave = midiNumber / 12 - 1
        return "${NOTE_NAMES[index]}$octave"
    }

    fun generate(track: MelodyTrack): String {
        val ppq = track.ticksPerQuarterNote.coerceAtLeast(4)
        val grid = (ppq / 4).toLong()
        fun tickToGrid(tick: Long) = (tick + grid / 2) / grid

        val notes = quantizeNotes(track, ::tickToGrid)
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
                val active = notes.getOrNull(noteIndex)?.takeIf { it.start <= pos && pos < it.end }
                val beatProps = midBarTempos[pos]?.let { "{tempo $it}" } ?: ""
                val nextTempoBoundary = midBarTempos.keys.filter { it > pos }.minOrNull() ?: Long.MAX_VALUE
                val segEnd = if (active != null) {
                    minOf(barEndGrid, active.end, nextTempoBoundary)
                } else {
                    minOf(barEndGrid, notes.getOrNull(noteIndex)?.start ?: Long.MAX_VALUE, nextTempoBoundary)
                }
                val segLength = segEnd - pos
                if (segLength <= 0) break
                if (active != null) {
                    tokens.addAll(noteTokens(noteName(active.noteNumber), pos - bar.startGrid, segLength, tie = pos > active.start, firstPieceBeatProps = beatProps))
                } else {
                    tokens.addAll(restTokens(segLength, firstPieceBeatProps = beatProps))
                }
                pos = segEnd
            }
            tokens.add("|")
        }
        return tokens.joinToString(" ")
    }

    private fun quantizeNotes(track: MelodyTrack, tickToGrid: (Long) -> Long): List<QNote> {
        val raw = track.notes
            .sortedBy { it.startTick }
            .filter { it.endTick > 0 }
            .map { note ->
                val start = tickToGrid(note.startTick.coerceAtLeast(0))
                QNote(note.noteNumber, start, maxOf(start + 1, tickToGrid(note.endTick)))
            }
        // Clamp overlaps (the track is monophonic, but quantization may round a
        // note end past the next note's start)
        return raw.mapIndexedNotNull { index, note ->
            val clampedEnd = if (index + 1 < raw.size) minOf(note.end, raw[index + 1].start) else note.end
            if (clampedEnd > note.start) note.copy(end = clampedEnd) else null
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

    /** Decomposes [gridLength] into tied note pieces, respecting beat alignment. */
    private fun noteTokens(
        name: String,
        startGridInBar: Long,
        gridLength: Long,
        tie: Boolean,
        firstPieceBeatProps: String = ""
    ): List<String> {
        val tokens = mutableListOf<String>()
        var remaining = gridLength
        var pos = startGridInBar
        var first = true
        while (remaining > 0) {
            val piece = DURATION_PIECES.first { it.gridLength <= remaining && pos % it.align == 0L }
            val tied = if (first && !tie) "" else "{t}"
            val beatProps = if (first) firstPieceBeatProps else ""
            tokens.add("$name$tied.${piece.value}${dotsSuffix(piece.dots)}$beatProps")
            remaining -= piece.gridLength
            pos += piece.gridLength
            first = false
        }
        return tokens
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
