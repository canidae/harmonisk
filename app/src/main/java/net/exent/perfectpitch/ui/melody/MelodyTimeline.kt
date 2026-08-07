package net.exent.perfectpitch.ui.melody

import net.exent.perfectpitch.midi.MelodyTrack

/**
 * Returns the start time (seconds) of the note the sheet music cursor should anchor to
 * for a given view time: the note active at [viewTimeSeconds], or the first note
 * starting at/after it. Falls back to [viewTimeSeconds] when there is no note there.
 */
internal fun nearestNoteStartSeconds(track: MelodyTrack, viewTimeSeconds: Double): Double {
    val active = track.getNoteAtTime(viewTimeSeconds)
    if (active != null) return track.noteStartSeconds(active)
    return track.notes
        .map { track.noteStartSeconds(it) }
        .filter { it >= viewTimeSeconds }
        .minOrNull() ?: viewTimeSeconds
}
