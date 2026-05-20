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

    /** Returns the BPM at the given [tick] position. */
    fun bpmAt(tick: Long): Double {
        val event = tempoChanges.lastOrNull { it.tick <= tick }
            ?: tempoChanges.firstOrNull()
            ?: return 120.0
        return event.bpm
    }

    /** Returns the active [TimeSignatureEvent] at the given [tick] position. */
    fun timeSignatureAt(tick: Long): TimeSignatureEvent {
        return timeSignatures.lastOrNull { it.tick <= tick }
            ?: timeSignatures.firstOrNull()
            ?: TimeSignatureEvent(tick = 0, numerator = 4, denominator = 4)
    }

    /** Approximate inverse of [tickToSeconds]: converts a time in seconds to the nearest tick. */
    fun secondsToTick(seconds: Double): Long {
        if (seconds <= 0.0) return 0L
        var remainingSeconds = seconds
        var currentTick = 0L
        var currentUsPerBeat = tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L

        for (i in tempoChanges.indices) {
            val tempo = tempoChanges[i]
            val nextTick = if (i + 1 < tempoChanges.size) tempoChanges[i + 1].tick else Long.MAX_VALUE
            val segmentTicks = nextTick - tempo.tick
            val segmentSeconds = segmentTicks.toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)

            if (tempo.tick >= currentTick) {
                currentUsPerBeat = tempo.microsecondsPerBeat
            }

            if (remainingSeconds <= segmentSeconds) {
                return currentTick + (remainingSeconds * ticksPerQuarterNote * 1_000_000.0 / currentUsPerBeat).toLong()
            }
            remainingSeconds -= segmentSeconds
            currentTick = nextTick
        }

        return currentTick + (remainingSeconds * ticksPerQuarterNote * 1_000_000.0 / currentUsPerBeat).toLong()
    }
}

