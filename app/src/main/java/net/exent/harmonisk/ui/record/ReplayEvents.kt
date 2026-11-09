package net.exent.harmonisk.ui.record

internal data class ReplayEvent(
    val timeMs: Long,
    val midiNote: Int,
    val isOn: Boolean,
    val trackOrigin: Int = 0
)

internal object ReplayEvents {

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
