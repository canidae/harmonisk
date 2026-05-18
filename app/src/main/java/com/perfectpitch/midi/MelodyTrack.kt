package com.perfectpitch.midi

/** Parsed melody track from a MIDI file. */
data class MelodyTrack(
    val name: String,
    val notes: List<NoteEvent>,
    val tempoChanges: List<TempoEvent>,
    val timeSignatures: List<TimeSignatureEvent>,
    val ticksPerQuarterNote: Int
) {
    fun tickToSeconds(tick: Long): Double {
        var seconds = 0.0
        var lastTick = 0L
        var currentUsPerBeat = tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L

        for (tempo in tempoChanges) {
            if (tempo.tick >= tick) break
            seconds += (tempo.tick - lastTick).toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)
            lastTick = tempo.tick
            currentUsPerBeat = tempo.microsecondsPerBeat
        }

        seconds += (tick - lastTick).toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)
        return seconds
    }

    fun getNoteAtTime(seconds: Double): NoteEvent? =
        notes.find { note ->
            val start = noteStartSeconds(note)
            seconds >= start && seconds < start + noteDurationSeconds(note)
        }

    val durationSeconds: Double
        get() = tickToSeconds(notes.maxOfOrNull { it.endTick } ?: 0L)

    val noteRange: IntRange
        get() {
            val min = notes.minOfOrNull { it.noteNumber } ?: 0
            val max = notes.maxOfOrNull { it.noteNumber } ?: 127
            return min..max
        }

    fun noteDurationSeconds(note: NoteEvent): Double =
        tickToSeconds(note.endTick) - tickToSeconds(note.startTick)

    fun noteStartSeconds(note: NoteEvent): Double = tickToSeconds(note.startTick)

    /** Returns a new track with all notes transposed by [semitones], clamped to MIDI range 0–127. */
    fun transpose(semitones: Int): MelodyTrack {
        if (semitones == 0) return this
        return copy(notes = notes.map { it.copy(noteNumber = (it.noteNumber + semitones).coerceIn(0, 127)) })
    }
}

