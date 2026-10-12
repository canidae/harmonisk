package net.exent.harmonisk.midi

class MelodyTrack(
    val name: String,
    val notes: List<NoteEvent>,
    val tempoChanges: List<TempoEvent>,
    val timeSignatures: List<TimeSignatureEvent>,
    val ticksPerQuarterNote: Int,
    val channel: Int = 0,
    val isMetronome: Boolean = false,
    val keySignature: KeySignature? = null
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

    private data class TempoSegment(
        val startTick: Long,
        val endTick: Long,
        val startSecond: Double,
        val usPerBeat: Long
    )

    private val segments: List<TempoSegment> by lazy {
        if (tempoChanges.isEmpty()) {
            listOf(TempoSegment(0, Long.MAX_VALUE, 0.0, 500_000L))
        } else {
            val sortedTempos = tempoChanges.sortedBy { it.tick }
            buildList {
                for (i in sortedTempos.indices) {
                    val t = sortedTempos[i]
                    val nextTick = if (i + 1 < sortedTempos.size) sortedTempos[i + 1].tick else Long.MAX_VALUE
                    val startSec = if (i == 0) {
                        0.0
                    } else {
                        val prev = get(i - 1)
                        prev.startSecond + (prev.endTick - prev.startTick).toDouble() * prev.usPerBeat / (ticksPerQuarterNote * 1_000_000.0)
                    }
                    add(TempoSegment(t.tick, nextTick, startSec, t.microsecondsPerBeat))
                }
            }
        }
    }

    private val noteStartSecondsCache: DoubleArray by lazy {
        DoubleArray(sortedNotes.size) { i ->
            tickToSeconds(sortedNotes[i].startTick)
        }
    }

    fun tickToSeconds(tick: Long): Double {
        val idx = segments.binarySearch { it.startTick.compareTo(tick) }
        val seg = if (idx >= 0) {
            segments[idx]
        } else {
            val insertionPoint = -(idx + 1)
            segments[if (insertionPoint == 0) 0 else insertionPoint - 1]
        }
        if (tick >= seg.endTick) {
            return seg.startSecond + (seg.endTick - seg.startTick).toDouble() * seg.usPerBeat / (ticksPerQuarterNote * 1_000_000.0)
        }
        return seg.startSecond + (tick - seg.startTick).toDouble() * seg.usPerBeat / (ticksPerQuarterNote * 1_000_000.0)
    }

    fun getNoteAtTime(seconds: Double): NoteEvent? {
        var lo = 0
        var hi = sortedNotes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (noteStartSecondsCache[mid] <= seconds) lo = mid + 1 else hi = mid - 1
        }
        if (hi < 0) return null
        val candidate = sortedNotes[hi]
        val start = noteStartSecondsCache[hi]
        val end = tickToSeconds(candidate.endTick)
        return if (seconds < end) candidate else null
    }

    fun noteDurationSeconds(note: NoteEvent): Double =
        tickToSeconds(note.endTick) - tickToSeconds(note.startTick)

    fun noteStartSeconds(note: NoteEvent): Double = tickToSeconds(note.startTick)

    /** Indices (into [notes]) of all notes active at the given time, i.e. those whose window contains it. */
    fun activeNoteIndicesAt(seconds: Double): List<Int> {
        val result = mutableListOf<Int>()
        for ((i, note) in notes.withIndex()) {
            val start = tickToSeconds(note.startTick)
            if (seconds < start) continue
            if (seconds < tickToSeconds(note.endTick)) result.add(i)
        }
        return result
    }

    fun transpose(semitones: Int): MelodyTrack {
        if (semitones == 0) return this
        return MelodyTrack(
            name = name,
            notes = notes.map { it.copy(noteNumber = (it.noteNumber + semitones).coerceIn(0, 127)) },
            tempoChanges = tempoChanges,
            timeSignatures = timeSignatures,
            ticksPerQuarterNote = ticksPerQuarterNote,
            channel = channel,
            isMetronome = isMetronome,
            keySignature = keySignature?.transpose(semitones)
        )
    }

    fun bpmAt(tick: Long): Double {
        val event = tempoChanges.lastOrNull { it.tick <= tick }
            ?: tempoChanges.firstOrNull()
            ?: return 120.0
        return event.bpm
    }

    fun timeSignatureAt(tick: Long): TimeSignatureEvent {
        return timeSignatures.lastOrNull { it.tick <= tick }
            ?: timeSignatures.firstOrNull()
            ?: TimeSignatureEvent(tick = 0, numerator = 4, denominator = 4)
    }

    fun secondsToTick(seconds: Double): Long {
        if (seconds <= 0.0) return 0L
        val idx = segments.binarySearch { it.startSecond.compareTo(seconds) }
        val seg = if (idx >= 0) {
            segments[idx]
        } else {
            val insertionPoint = -(idx + 1)
            segments[if (insertionPoint == 0) 0 else insertionPoint - 1]
        }
        val elapsedInSegment = seconds - seg.startSecond
        val segmentEndSec = seg.startSecond + (seg.endTick - seg.startTick).toDouble() * seg.usPerBeat / (ticksPerQuarterNote * 1_000_000.0)
        if (seconds >= segmentEndSec) {
            return seg.endTick
        }
        return seg.startTick + (elapsedInSegment * ticksPerQuarterNote * 1_000_000.0 / seg.usPerBeat).toLong()
    }

}
