package net.exent.harmonisk.ui.freeplay

/** A scheduled note-on or note-off in the Free Play playback engine. */
internal data class ReplayEvent(
    val timeMs: Long,
    val midiNote: Int,
    val isOn: Boolean,
    val trackOrigin: Int = 0
)

internal object ReplayEvents {

    /**
     * Note events for the span from [startTimeMs] up to [loopEndMs].
     *
     * A note that already began before [startTimeMs] is left out: starting playback in the
     * middle of the song must not retrigger it, or the tone sustains from the seek point.
     * End times past the loop end are clamped, so a note never outlives the loop it plays in.
     */
    fun notes(
        playedNotes: List<PlayedNote>,
        startTimeMs: Long,
        loopEndMs: Long
    ): List<ReplayEvent> {
        val events = mutableListOf<ReplayEvent>()
        for (note in playedNotes.sortedBy { it.startTimeMs }) {
            val endMs = note.endTimeMs ?: continue
            if (note.startTimeMs < startTimeMs || note.startTimeMs >= loopEndMs) continue
            if (endMs <= startTimeMs) continue
            events.add(ReplayEvent(note.startTimeMs, note.midiNumber, true, note.trackOrigin))
            events.add(ReplayEvent(minOf(endMs, loopEndMs), note.midiNumber, false, note.trackOrigin))
        }
        return events
    }
}
