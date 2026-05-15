package com.perfectpitch.midi

/**
 * Represents a parsed melody track from a MIDI file.
 * Contains note events and tempo information needed for playback.
 */
data class MelodyTrack(
    val name: String,
    val notes: List<NoteEvent>,
    val tempoChanges: List<TempoEvent>,
    val timeSignatures: List<TimeSignatureEvent>,
    val ticksPerQuarterNote: Int
) {
    /**
     * Convert a tick position to seconds, accounting for tempo changes.
     */
    fun tickToSeconds(tick: Long): Double {
        if (tempoChanges.isEmpty()) {
            val defaultUsPerBeat = 500_000L // 120 BPM default
            return tick.toDouble() * defaultUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)
        }

        var seconds = 0.0
        var lastTick = 0L
        var currentUsPerBeat = tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L

        for (tempo in tempoChanges) {
            if (tempo.tick >= tick) break
            val deltaTicks = tempo.tick - lastTick
            seconds += deltaTicks.toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)
            lastTick = tempo.tick
            currentUsPerBeat = tempo.microsecondsPerBeat
        }

        val remainingTicks = tick - lastTick
        seconds += remainingTicks.toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)
        return seconds
    }

    /**
     * Convert seconds to tick position.
     */
    fun secondsToTick(seconds: Double): Long {
        if (tempoChanges.isEmpty()) {
            val defaultUsPerBeat = 500_000L
            return (seconds * ticksPerQuarterNote * 1_000_000.0 / defaultUsPerBeat).toLong()
        }

        var remainingSeconds = seconds
        var currentTick = 0L
        var currentUsPerBeat = tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L

        for (i in tempoChanges.indices) {
            val tempo = tempoChanges[i]
            if (i > 0) {
                val deltaTicks = tempo.tick - currentTick
                val deltaSeconds = deltaTicks.toDouble() * currentUsPerBeat / (ticksPerQuarterNote * 1_000_000.0)
                if (deltaSeconds >= remainingSeconds) {
                    break
                }
                remainingSeconds -= deltaSeconds
                currentTick = tempo.tick
                currentUsPerBeat = tempo.microsecondsPerBeat
            }
        }

        val additionalTicks = (remainingSeconds * ticksPerQuarterNote * 1_000_000.0 / currentUsPerBeat).toLong()
        return currentTick + additionalTicks
    }

    /**
     * Get the note active at a given time in seconds.
     */
    fun getNoteAtTime(seconds: Double): NoteEvent? {
        val tick = secondsToTick(seconds)
        return notes.find { it.startTick <= tick && it.endTick > tick }
    }

    /**
     * Total duration of the track in seconds.
     */
    val durationSeconds: Double
        get() {
            val lastNoteTick = notes.maxOfOrNull { it.endTick } ?: 0L
            return tickToSeconds(lastNoteTick)
        }

    /**
     * Note range (lowest to highest MIDI note number).
     */
    val noteRange: IntRange
        get() {
            val min = notes.minOfOrNull { it.noteNumber } ?: 0
            val max = notes.maxOfOrNull { it.noteNumber } ?: 127
            return min..max
        }

    /**
     * Get note duration in seconds.
     */
    fun noteDurationSeconds(note: NoteEvent): Double {
        return tickToSeconds(note.endTick) - tickToSeconds(note.startTick)
    }

    /**
     * Get note start time in seconds.
     */
    fun noteStartSeconds(note: NoteEvent): Double {
        return tickToSeconds(note.startTick)
    }
}
