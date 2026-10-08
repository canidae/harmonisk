package net.exent.harmonisk.midi

/**
 * Data classes representing parsed MIDI events.
 */
data class NoteEvent(
    val noteNumber: Int,
    val velocity: Int,
    val startTick: Long,
    val durationTicks: Long
) {
    val endTick: Long get() = startTick + durationTicks
}

data class TempoEvent(
    val tick: Long,
    val microsecondsPerBeat: Long
) {
    val bpm: Double get() = 60_000_000.0 / microsecondsPerBeat
}

data class TimeSignatureEvent(
    val tick: Long,
    val numerator: Int,
    val denominator: Int
)
