package net.exent.perfectpitch.ui.melody

/**
 * Tracks whether the note the game expects right now has actually been attacked by the player.
 *
 * Melodies repeat the same pitch (C C C), and pitch detection alone cannot tell a new note from one
 * tone that is simply held. A repeated pitch therefore only counts once the input has heard the tone
 * stop for at least [minSilenceMs]: that silence is the articulation.
 *
 * The game calls [startNote] when a new note becomes the expected one, and reports the pitch frames it
 * hears through [onValidPitchFrame]. The silence is timed between heard frames, so the pause is found
 * whether the player lifted a finger late, right on the beat, or early enough that the tone is
 * sounding again by the time the note comes due.
 *
 * A break has to last [minSilenceMs] because pitch frames are dropped from time to time, and a
 * dropped frame is not a played pause.
 */
class NoteArticulationTracker(
    private val minSilenceMs: Double = DEFAULT_MIN_SILENCE_MS
) {
    companion object {
        /**
         * Shortest silence that reads as a re-attack rather than a dropped pitch frame.
         *
         * Two things set this. A couple of frames in a row can be lost at any time, which is roughly
         * 25 ms, and the detector needs its 64 ms analysis window to refill before it hears a new note
         * at all, so a re-attacked note is not heard again until well after the player's pause. A 16th
         * note at 120 bpm is 125 ms, so this still leaves room for fast repeated playing.
         */
        const val DEFAULT_MIN_SILENCE_MS = 35.0
    }

    /** When the last pitch frame arrived, or [Double.NEGATIVE_INFINITY] before the first one. */
    private var lastFrameMs = Double.NEGATIVE_INFINITY

    /** When the tone now sounding started, or [Double.NEGATIVE_INFINITY] before the first frame. */
    private var soundStartedMs = Double.NEGATIVE_INFINITY

    /**
     * True while the expected note continues the tone the player was already sounding, so it must not
     * be counted until the player breaks that tone.
     */
    var isAwaitingPause = false
        private set

    /**
     * The game now expects [noteNumber] instead of [previousNoteNumber]. [nowMs] must be a monotonic
     * clock reading shared with every other call. [previousNoteNumber] is -1 for the first note of the
     * melody and after a reset; only [previousNoteNumber] decides whether this is a repeated pitch,
     * because the same note can be expected before any frame of it is heard.
     */
    fun startNote(noteNumber: Int, previousNoteNumber: Int, nowMs: Double) {
        // The tone sounding now started at least a pause ago, so whatever the player was holding has
        // already been broken: either they lifted early, or they stopped and re-attacked.
        val toneBrokeBeforeNote = nowMs - soundStartedMs >= minSilenceMs
        // A repeated pitch that is still sounding is one tone, not two notes; a different pitch is a
        // new attack even if the player never stopped playing.
        isAwaitingPause = noteNumber == previousNoteNumber && !toneBrokeBeforeNote
    }

    /** A pitch frame of the note the game expects was detected at [nowMs]. */
    fun onValidPitchFrame(nowMs: Double) {
        val silenceSinceLastFrame = if (lastFrameMs == Double.NEGATIVE_INFINITY) minSilenceMs
        else (nowMs - lastFrameMs).coerceAtLeast(0.0)
        if (silenceSinceLastFrame >= minSilenceMs && isAwaitingPause) {
            // The tone the player was holding went unheard for long enough to be a new attack.
            isAwaitingPause = false
        }
        // The tone has been sounding since the previous frame, or since the silence before it.
        soundStartedMs = nowMs - silenceSinceLastFrame
        lastFrameMs = nowMs
    }

    fun reset() {
        lastFrameMs = Double.NEGATIVE_INFINITY
        soundStartedMs = Double.NEGATIVE_INFINITY
        isAwaitingPause = false
    }
}
