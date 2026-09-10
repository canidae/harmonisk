package net.exent.perfectpitch.ui.melody

/**
 * Tracks whether the note the game expects right now has actually been attacked by the player.
 *
 * Melodies repeat the same pitch (D D), and pitch detection alone cannot tell a new note from one
 * tone that is simply held. A repeated pitch therefore only counts once the input has heard the tone
 * stop for at least [minSilenceMs]: that silence is the articulation.
 *
 * The game calls [expectNote] when the expected note changes, and reports every frame it hears a tone
 * in through [onToneHeard]: a pitch the detector found, or a frame where only the level says a note is
 * sounding. The silence is timed between heard frames, so the pause is found whether the player lifted
 * a finger late, right on the beat, or early enough that the tone is sounding again by the time the
 * note comes due.
 */
class NoteArticulationTracker(
    private val minSilenceMs: Double = DEFAULT_MIN_SILENCE_MS
) {
    companion object {
        /** No note is expected: a rest, or the gap the notation leaves between two notes. */
        const val NO_NOTE = -1

        /**
         * Shortest silence that reads as a re-attack rather than a dropped pitch frame.
         *
         * Measured on a Pixel 7 Pro with one tone held for 16 s (the microphone hearing the device's
         * own speaker), the detector lost the pitch for up to 34 ms at a time without the tone ever
         * stopping — dropping frames, not sound. A played pause is different in kind: the detector
         * only hears the next note once its 64 ms analysis window has refilled, so a real
         * articulation shows up as a gap of 100 ms or more. This threshold sits in the empty space
         * between the two, ignoring the dropouts without demanding a long pause from the player. A
         * 16th note at 120 bpm lasts 125 ms, so fast repeated playing still fits.
         */
        const val DEFAULT_MIN_SILENCE_MS = 60.0
    }

    /** When the last pitch frame arrived, or [Double.NEGATIVE_INFINITY] before the first one. */
    private var lastFrameMs = Double.NEGATIVE_INFINITY

    /** When the tone now sounding started, or [Double.NEGATIVE_INFINITY] before the first frame. */
    private var soundStartedMs = Double.NEGATIVE_INFINITY

    /** The pitch the game last asked for, or [NO_NOTE] before the melody starts. */
    private var lastExpectedNote = NO_NOTE

    /**
     * True while the expected note continues the tone the player was already sounding, so it must not
     * be counted until the player breaks that tone.
     */
    var isAwaitingPause = false
        private set

    /**
     * The game now expects [noteNumber] instead of whatever it expected before. [nowMs] must be a
     * monotonic clock reading shared with every other call.
     *
     * [NO_NOTE] — a rest, or the silent gap the notation leaves between two notes — changes nothing.
     * Holding a tone across a rest is still one tone, so the note after the rest is credited only if
     * the player broke it; a rest must not launder a held tone into a new attack.
     */
    fun expectNote(noteNumber: Int, nowMs: Double) {
        if (noteNumber == NO_NOTE) return
        // The tone sounding now started at least a pause ago, so whatever the player was holding has
        // already been broken: either they lifted early, or they stopped and re-attacked.
        val toneBrokeBeforeNote = nowMs - soundStartedMs >= minSilenceMs
        // A repeated pitch that is still sounding is one tone, not two notes; a different pitch is a
        // new attack even if the player never stopped playing.
        isAwaitingPause = noteNumber == lastExpectedNote && !toneBrokeBeforeNote
        lastExpectedNote = noteNumber
    }

    /**
     * The player has a tone going at [nowMs]. A note does not stop just because the detector had
     * nothing to say about a frame, so a frame where only the level shows a note is reported here too.
     */
    fun onToneHeard(nowMs: Double) {
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
        lastExpectedNote = NO_NOTE
        isAwaitingPause = false
    }
}
