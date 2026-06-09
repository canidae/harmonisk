package net.exent.perfectpitch.midi

/** Parsed melody track from a MIDI file. */
class MelodyTrack(
    val name: String,
    val notes: List<NoteEvent>,
    val tempoChanges: List<TempoEvent>,
    val timeSignatures: List<TimeSignatureEvent>,
    val ticksPerQuarterNote: Int
) {
    private val sortedNotes: List<NoteEvent> by lazy { notes.sortedBy { it.startTick } }

    val durationSeconds: Double by lazy {
        tickToSeconds(notes.maxOfOrNull { it.endTick } ?: 0L)
    }

    val noteRange: IntRange by lazy {
        val min = notes.minOfOrNull { it.noteNumber } ?: 0
        val max = notes.maxOfOrNull { it.noteNumber } ?: 127
        min..max
    }

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

    fun getNoteAtTime(seconds: Double): NoteEvent? {
        // Binary search: find the last note starting at or before `seconds`
        var lo = 0
        var hi = sortedNotes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (noteStartSeconds(sortedNotes[mid]) <= seconds) lo = mid + 1 else hi = mid - 1
        }
        if (hi < 0) return null
        val candidate = sortedNotes[hi]
        val start = noteStartSeconds(candidate)
        return if (seconds < start + noteDurationSeconds(candidate)) candidate else null
    }

    fun noteDurationSeconds(note: NoteEvent): Double =
        tickToSeconds(note.endTick) - tickToSeconds(note.startTick)

    fun noteStartSeconds(note: NoteEvent): Double = tickToSeconds(note.startTick)

    /** Returns a new track with all notes transposed by [semitones], clamped to MIDI range 0–127. */
    fun transpose(semitones: Int): MelodyTrack {
        if (semitones == 0) return this
        return MelodyTrack(
            name = name,
            notes = notes.map { it.copy(noteNumber = (it.noteNumber + semitones).coerceIn(0, 127)) },
            tempoChanges = tempoChanges,
            timeSignatures = timeSignatures,
            ticksPerQuarterNote = ticksPerQuarterNote
        )
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
            currentUsPerBeat = tempo.microsecondsPerBeat
            val segmentSeconds = segmentTicks.toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)

            if (remainingSeconds <= segmentSeconds) {
                return currentTick + (remainingSeconds * ticksPerQuarterNote * 1_000_000.0 / currentUsPerBeat).toLong()
            }
            remainingSeconds -= segmentSeconds
            currentTick = nextTick
        }

        return currentTick + (remainingSeconds * ticksPerQuarterNote * 1_000_000.0 / currentUsPerBeat).toLong()
    }
}
