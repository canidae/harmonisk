package net.exent.perfectpitch.ui.melody

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract of [NoteArticulationTracker]: a note that repeats the pitch the player is
 * already sounding only counts once the input has heard the tone stop for a moment.
 */
class NoteArticulationTrackerTest {

    private companion object {
        /** Microphone capture hop: one detected pitch frame every 512 samples at 44100 Hz. */
        const val FRAME_MS = 11.6

        const val MIN_SILENCE_MS = 60.0

        const val REST = NoteArticulationTracker.NO_NOTE
        const val C4 = 60
        const val D4 = 62

        /** Half a second of a held note. */
        const val HOLD_MS = 500.0

        /** A pause the detector can hear, and that a player repeating a note would make. */
        const val CLEAR_PAUSE_MS = 120.0
    }

    private fun tracker() = NoteArticulationTracker(minSilenceMs = MIN_SILENCE_MS)

    /** The game now expects [note] at [atMs], where [note] may be [REST]. */
    private fun expect(tracker: NoteArticulationTracker, note: Int, atMs: Double) {
        tracker.expectNote(note, atMs)
    }

    /** Pitch frames arrive from [fromMs] every hop for [durationMs]. */
    private fun frames(
        tracker: NoteArticulationTracker,
        fromMs: Double,
        durationMs: Double = HOLD_MS
    ): Double {
        var t = fromMs
        while (t < fromMs + durationMs) {
            tracker.onValidPitchFrame(t)
            t += FRAME_MS
        }
        return t
    }

    /** The game expects [note] at [atMs] and the player attacks it [pauseMs] later, out of silence. */
    private fun attack(
        tracker: NoteArticulationTracker,
        note: Int,
        atMs: Double,
        pauseMs: Double = CLEAR_PAUSE_MS,
        durationMs: Double = HOLD_MS
    ): Double {
        expect(tracker, note, atMs)
        return frames(tracker, atMs + pauseMs, durationMs)
    }

    @Test
    fun `first note of a melody needs no preceding pause`() {
        val tracker = tracker()

        expect(tracker, C4, 0.0)
        assertFalse(tracker.isAwaitingPause)

        tracker.onValidPitchFrame(0.0)
        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `continuous tone held into a repeated note is not accepted`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        // The game expects the same pitch again and the player never stopped sounding it, so the next
        // frame arrives one hop after the last one.
        expect(tracker, C4, t)

        assertTrue("a held tone must not count as a new note", tracker.isAwaitingPause)

        frames(tracker, t + FRAME_MS)
        assertTrue(tracker.isAwaitingPause)
    }

    @Test
    fun `continuous tone is never accepted no matter how many notes it is held through`() {
        val tracker = tracker()
        var t = attack(tracker, C4, 0.0)

        repeat(8) {
            expect(tracker, C4, t)
            t = frames(tracker, t + FRAME_MS)
        }

        assertTrue(tracker.isAwaitingPause)
    }

    /**
     * The notation of a melody usually leaves a little silence between two notes, and the game reports
     * no expected note for it. That gap is far too short to be a played pause, so it must not make the
     * tone the player is holding look like a fresh attack — which is exactly what "Jeg Kan Spille
     * Fiolin" does between its opening two D4 notes.
     */
    @Test
    fun `a short rest between notes does not turn a held tone into a new attack`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        // The tone is still sounding, but the notation leaves a 26 ms gap with no note expected.
        expect(tracker, REST, t)
        frames(tracker, t, 2 * FRAME_MS)
        expect(tracker, C4, t + 2 * FRAME_MS)

        assertTrue("a rest must not launder a held tone into a new note", tracker.isAwaitingPause)

        frames(tracker, t + 3 * FRAME_MS)
        assertTrue(tracker.isAwaitingPause)
    }

    @Test
    fun `a rest keeps the pitch the game expected before it`() {
        val tracker = tracker()
        var t = attack(tracker, C4, 0.0)

        // C4, a rest, then C4 again: the rest changes nothing, so the repeat still needs a pause.
        expect(tracker, REST, t)
        t = frames(tracker, t, 3 * FRAME_MS)
        expect(tracker, C4, t)
        assertTrue(tracker.isAwaitingPause)

        // C4, a rest, then D4: a different pitch is a new attack whether the player paused or not.
        t = frames(tracker, t + FRAME_MS)
        expect(tracker, REST, t)
        t = frames(tracker, t, 3 * FRAME_MS)
        expect(tracker, D4, t)
        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `a repeated note after a rest is accepted when the player stopped during the rest`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        // The player stops at the end of the first note and re-attacks the same pitch later, with a
        // rest reported in between: the silence is heard either way.
        expect(tracker, REST, t)
        expect(tracker, C4, t + CLEAR_PAUSE_MS)
        frames(tracker, t + CLEAR_PAUSE_MS)

        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `pause across the note boundary is accepted`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        attack(tracker, C4, t)

        assertFalse("the player lifted a finger, so this is a new note", tracker.isAwaitingPause)
    }

    @Test
    fun `pause that ends before the note boundary is accepted`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        // The player stopped early and waited, so the tone sounds again right after the previous note
        // and is held through the boundary.
        expect(tracker, C4, t + CLEAR_PAUSE_MS)
        frames(tracker, t + CLEAR_PAUSE_MS)

        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `pause inside the previous note is accepted`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        // The player lifts and re-attacks inside the previous note, then holds that tone through the
        // boundary: the same pitch is sounding, but it has already been broken once.
        tracker.onValidPitchFrame(t + CLEAR_PAUSE_MS)
        expect(tracker, C4, t + CLEAR_PAUSE_MS + FRAME_MS)

        assertFalse(tracker.isAwaitingPause)

        tracker.onValidPitchFrame(t + CLEAR_PAUSE_MS + 2 * FRAME_MS)
        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `silence of exactly the minimum is accepted`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        attack(tracker, C4, t, pauseMs = MIN_SILENCE_MS)

        assertFalse(tracker.isAwaitingPause)
    }

    /**
     * The detector drops frames while a note is held: the game measured runs of up to 34 ms on a
     * device, with no pause in the sound at all.
     */
    @Test
    fun `dropped frames are not a played pause`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        expect(tracker, C4, t)
        tracker.onValidPitchFrame(t + 4 * FRAME_MS)

        assertTrue("a dropped frame is not a played pause", tracker.isAwaitingPause)
    }

    @Test
    fun `moving to a different pitch counts without a pause`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        attack(tracker, D4, t, pauseMs = FRAME_MS)

        assertFalse("a different pitch is a new attack by definition", tracker.isAwaitingPause)
    }

    @Test
    fun `returning to a pitch after a different note needs no pause`() {
        val tracker = tracker()
        var t = attack(tracker, C4, 0.0)

        t = attack(tracker, D4, t, pauseMs = FRAME_MS)
        attack(tracker, C4, t, pauseMs = FRAME_MS)

        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `note heard after a long rest is a new attack`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        attack(tracker, C4, t + 2000.0, pauseMs = FRAME_MS)

        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `accepted note stays accepted while it is held`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)

        attack(tracker, C4, t)
        assertFalse(tracker.isAwaitingPause)

        // The player keeps holding the accepted note, which is scored for as long as it lasts.
        frames(tracker, t + CLEAR_PAUSE_MS + HOLD_MS)
        assertFalse(tracker.isAwaitingPause)
    }

    @Test
    fun `chain of repeated notes only accepts the ones that were paused`() {
        val tracker = tracker()
        var t = attack(tracker, C4, 0.0)

        // Second C: held through.
        expect(tracker, C4, t)
        t = frames(tracker, t + FRAME_MS)
        assertTrue("second C must not count", tracker.isAwaitingPause)

        // Third C: the player breaks the tone.
        t = attack(tracker, C4, t)
        assertFalse("third C was articulated", tracker.isAwaitingPause)

        // Fourth C: held through the third one.
        expect(tracker, C4, t)
        frames(tracker, t + FRAME_MS)
        assertTrue("fourth C must not count", tracker.isAwaitingPause)
    }

    @Test
    fun `pausing once does not carry over to the following note`() {
        val tracker = tracker()
        var t = attack(tracker, C4, 0.0)

        t = attack(tracker, C4, t)
        assertFalse(tracker.isAwaitingPause)

        // The player keeps sounding the same pitch: one pause must not unlock every later repeat.
        expect(tracker, C4, t)
        frames(tracker, t + FRAME_MS)
        assertTrue(tracker.isAwaitingPause)
    }

    @Test
    fun `reset forgets the previous run`() {
        val tracker = tracker()
        val t = attack(tracker, C4, 0.0)
        expect(tracker, C4, t)
        assertTrue(tracker.isAwaitingPause)

        tracker.reset()
        attack(tracker, C4, t + HOLD_MS)

        assertFalse("a new attempt starts with a fresh first note", tracker.isAwaitingPause)
    }
}
