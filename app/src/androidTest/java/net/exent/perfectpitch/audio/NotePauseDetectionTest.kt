package net.exent.perfectpitch.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.PitchResult
import net.exent.perfectpitch.ui.melody.NoteArticulationTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Measures what a short pause between two notes of the same pitch looks like on the real detection
 * path, because Play by Melody credits a repeated note only when it hears such a pause.
 *
 * The audio is fed straight into [PitchProcessor.pitchFlow], i.e. the same framing, sliding window,
 * silence gate and CREPE model the microphone path uses. Each gap length is scored twice: how long
 * the detector stays off the expected pitch is the evidence the game can act on, and how often the
 * detector merely drops a frame says how short a real pause can be before it is indistinguishable
 * from detector noise.
 */
@RunWith(AndroidJUnit4::class)
class NotePauseDetectionTest {

    private companion object {
        private const val TAG = "PauseDetectTest"

        private const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE

        /** A4, the pitch both notes of every fixture are played at. */
        private const val A4_HZ = 440.0

        private const val A4_MIDI = 69

        /** Half a second of tone either side of the pause. */
        private const val NOTE_SECONDS = 0.5

        /** Pause lengths to try, in milliseconds. */
        private val GAP_MS_LIST = listOf(0, 10, 20, 30, 40, 50, 60, 80, 100, 150, 250)

        /** A frame this many cents off the played note counts as the detector missing it. */
        private const val TOLERANCE_CENTS = 50f

        /**
         * Silence the notation leaves between two notes, as "Jeg Kan Spille Fiolin" has between its
         * opening notes. Short enough that a tone held across it is still one tone.
         */
        private const val NOTATED_GAP_MS = 26.0

        /** How long the first note of the lingering fixture is played before it is released. */
        private const val PLAY_SECONDS = 0.35

        /** How long the released note takes to fall away, as a player's note does. */
        private const val RELEASE_SECONDS = 0.08

        /** Tail of the released note, relative to the played note, while it rings on. */
        private const val TAIL_DB = -12.0

        /** Silence this long between two heard pitches is the articulation of a repeated note. */
        private const val PAUSE_MS = NoteArticulationTracker.DEFAULT_MIN_SILENCE_MS
    }

    private lateinit var processor: PitchProcessor
    private lateinit var noteUtils: NoteUtils

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        noteUtils = NoteUtils()
        processor = PitchProcessor(
            audioCaptureService = AudioCaptureService(context),
            crepeDetector = CrepePitchDetector(context),
            noteUtils = noteUtils
        )
    }

    @Test
    fun repeatedNotePauseIsVisibleToTheDetector() {
        Log.i(TAG, "=== Repeated note on ${android.os.Build.MODEL}: pause vs. detected frames ===")

        for (gapMs in GAP_MS_LIST) {
            val frames = detect(pairedTone(gapMs))
            val missed = frames.map { !it.onPitch }
            val offPitchFrames = missed.count { it }

            Log.i(TAG, "gap=${gapMs}ms frames=${frames.size} offPitch=$offPitchFrames " +
                    "longestOffRun=${longestRun(missed)} pattern=${pattern(frames)}")

            assertTrue(
                "gap of ${gapMs}ms must not lose the note entirely",
                frames.any { it.onPitch }
            )
        }

        // A tone that is never broken must stay on the played pitch: that is what lets the game tell
        // one held note from two repeated ones.
        val held = heldTone()
        Log.i(TAG, "held tone: longestOffRun=${longestRun(held.map { !it.onPitch })} " +
                "pattern=${pattern(held)}")
        // The attack and release ramps at either end of the fixture are not pitch the player holds,
        // so only the middle, where the note boundary would be, has to stay on pitch.
        val middle = held.subList(8, held.size - 8)
        assertTrue(
            "a held tone must keep the detector on pitch across the note boundary",
            middle.all { it.onPitch }
        )
    }

    /** One continuous held A4, i.e. what playing two repeated notes without a pause sounds like. */
    private fun heldTone(): List<Frame> = detect(toneWithAttack(2 * NOTE_SECONDS))

    @Test
    fun articulationTrackerOnlyAcceptsHeardPauses() {
        Log.i(TAG, "=== Repeated note on ${android.os.Build.MODEL}: what the tracker accepts ===")
        val accepted = mutableMapOf<Int, Boolean>()
        for (gapMs in GAP_MS_LIST) {
            accepted[gapMs] = repeatedNoteAccepted(gapMs)
            Log.i(TAG, "gap=${gapMs}ms repeatedNoteAccepted=${accepted[gapMs]}")
        }
        Log.i(TAG, "the level gate adds the note's own release and attack to the pause it hears: a " +
                "played pause of 40 ms now registers, while a tone held across the boundary still " +
                "does not (its level never moves)")

        // A tone the player never broke must not count as a second note, and the pause a player makes
        // when repeating a note must be enough for the game to hear the note as a new one.
        assertFalse("a held tone must not count twice", accepted.getValue(0))
        assertTrue(
            "a played pause must register as a new note",
            GAP_MS_LIST.filter { it >= 100 }.all { accepted.getValue(it) }
        )
    }

    /**
     * A note the player releases without the sound stopping: the string, the body and the room ring
     * on, and the detector goes on reporting the pitch of a note nobody is playing. Nothing in the
     * pitch stream then says the player stopped, so the pause is never heard and the repeated note is
     * never credited — which is what the level gate is for.
     */
    @Test
    fun aReleasedNoteThatGoesOnRingingCountsAsThePause() {
        Log.i(TAG, "=== Repeated note on ${android.os.Build.MODEL}: first note released, still ringing ===")
        val frames = detect(lingeringNote())
        val tail = frames.filter { it.timeMs > (PLAY_SECONDS + RELEASE_SECONDS) * 1000 && it.timeMs < NOTE_SECONDS * 1000 }
        val gate = SoundingToneGate()
        val heard = frames.map { it.onPitch && gate.isSounding(it.levelDb?.toDouble()) }

        Log.i(TAG, "released note still detected on pitch: ${tail.count { it.onPitch }}/${tail.size} " +
                "frames, level ${"%.1f".format(tail.mapNotNull { it.levelDb }.average())}dB " +
                "(silence threshold ${PitchProcessor.SILENCE_THRESHOLD_DB}dB)")
        Log.i(TAG, "longest unheard run: without gate=${longestUnheardRun(frames, null)}ms " +
                "with gate=${longestUnheardRun(frames, heard)}ms, pause needed=${PAUSE_MS.toInt()}ms")
        Log.i(TAG, "repeated note accepted: without gate=${repeatedNoteAccepted(frames, null)} " +
                "with gate=${repeatedNoteAccepted(frames, heard)}")

        assertTrue(
            "the tail of the released note must still be heard as pitch, or there is no pause to miss",
            tail.last().onPitch
        )
        assertFalse(
            "pitch alone must not see the pause the player made",
            repeatedNoteAccepted(frames, null)
        )
        assertTrue(
            "the level must see the pause the player made",
            repeatedNoteAccepted(frames, heard)
        )
    }

    /**
     * What the game sees for a repeated A4 played with [gapMs] of silence between the two notes: the
     * first note is expected from the start, the notation leaves a short rest between the two, and the
     * repeat is expected from the note boundary. Every frame the detector gets right is reported to the
     * tracker the way the melody game reports it.
     */
    private fun repeatedNoteAccepted(gapMs: Int): Boolean {
        val frames = detect(pairedTone(gapMs))
        val gate = SoundingToneGate()
        return repeatedNoteAccepted(frames, frames.map { it.onPitch && gate.isSounding(it.levelDb?.toDouble()) })
    }

    /**
     * Whether the game hears [frames] as two notes, with [heard] saying which frames it treats as
     * sound: null to take every frame the detector got right, or the result of the level gate the
     * melody game runs over them.
     */
    private fun repeatedNoteAccepted(frames: List<Frame>, heard: List<Boolean>?): Boolean {
        val tracker = NoteArticulationTracker()
        // A melody's own timeline: the note, the gap the notation leaves after it (which the game
        // reports as no expected note), then the repeated note.
        val timeline = listOf(
            Expected(A4_MIDI, 0.0),
            Expected(NoteArticulationTracker.NO_NOTE, NOTE_SECONDS * 1000 - NOTATED_GAP_MS),
            Expected(A4_MIDI, NOTE_SECONDS * 1000)
        )
        var next = 0
        frames.forEachIndexed { index, frame ->
            while (next < timeline.size && timeline[next].atMs <= frame.timeMs) {
                tracker.expectNote(timeline[next].note, timeline[next].atMs)
                next++
            }
            if (heard?.get(index) ?: frame.onPitch) tracker.onToneHeard(frame.timeMs)
        }
        return !tracker.isAwaitingPause
    }

    private data class Expected(val note: Int, val atMs: Double)

    private data class Frame(val timeMs: Double, val onPitch: Boolean, val levelDb: Float?)

    /** Run the fixture through the production pitch flow and time each frame. */
    private fun detect(audio: FloatArray): List<Frame> = runBlocking {
        val results = processor.pitchFlow(TunerAudioFixture.chunks(audio).asFlow())
            .toList()
        val hopMs = AudioConstants.FRAME_DURATION_MS.toDouble()
        results.mapIndexed { index, result ->
            val cents = if (result.isValid) {
                abs(noteUtils.frequencyToCents(result.frequency, A4_HZ.toFloat()))
            } else {
                Float.MAX_VALUE
            }
            Frame(index * hopMs, result.isValid && cents <= TOLERANCE_CENTS, result.levelDb)
        }
    }

    /** Two notes of the same pitch, separated by [gapMs] of silence, each with a natural attack. */
    private fun pairedTone(gapMs: Int): FloatArray {
        val note = toneWithAttack(NOTE_SECONDS)
        val gapSamples = (gapMs / 1000.0 * SAMPLE_RATE).toInt()
        return note + FloatArray(gapSamples) + note
    }

    /**
     * A held A4 with a short attack and a clean release, so the boundary between two notes is a
     * real silence. A square envelope is deliberately avoided: a click is not a musical attack.
     */
    private fun toneWithAttack(seconds: Double, gainDb: Double = 0.0): FloatArray {
        val samples = (seconds * SAMPLE_RATE).toInt()
        val attack = (0.02 * SAMPLE_RATE).toInt()
        val release = (0.03 * SAMPLE_RATE).toInt()
        val out = FloatArray(samples)
        var peak = 0.0
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            var value = 0.0
            for (h in 1..8) value += sin(2 * PI * A4_HZ * h * t) / h
            out[i] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        val inv = (0.5 / peak * dBToLinear(gainDb)).toFloat()
        for (i in out.indices) {
            val envelope = when {
                i < attack -> i.toDouble() / attack
                i >= samples - release -> (samples - i).toDouble() / release
                else -> 1.0
            }
            out[i] *= inv * envelope.toFloat()
        }
        return out
    }

    /**
     * A note played, then released while it keeps ringing at [TAIL_DB] below the played note, then
     * played again. The tail sits well above the silence threshold, so the detector keeps reporting
     * it as pitch — the level is the only thing that says the note was released.
     *
     * One continuous signal, because splicing a quiet tail onto the note would leave a dip and a rise
     * at the join, and the detector's onset reset would drop frames there — inventing a pause.
     */
    private fun lingeringNote(): FloatArray {
        val releaseEnd = PLAY_SECONDS + RELEASE_SECONDS
        return toneWithLevels(NOTE_SECONDS + 0.5, listOf(
            0.0 to 0.0,
            PLAY_SECONDS to 0.0,
            releaseEnd to TAIL_DB,
            NOTE_SECONDS to TAIL_DB,
            NOTE_SECONDS + 0.03 to 0.0
        ))
    }

    /**
     * An A4 with a harmonic stack and a short attack, played at the gain given by [points]: a list of
     * (seconds, dB) joined by straight ramps in dB, so the note can be released and taken up again the
     * way a player does it, without a click.
     */
    private fun toneWithLevels(seconds: Double, points: List<Pair<Double, Double>>): FloatArray {
        val samples = (seconds * SAMPLE_RATE).toInt()
        val attack = (0.02 * SAMPLE_RATE).toInt()
        val out = FloatArray(samples)
        var peak = 0.0
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            var value = 0.0
            for (h in 1..8) value += sin(2 * PI * A4_HZ * h * t) / h
            out[i] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        val inv = (0.5 / peak).toFloat()
        for (i in 0 until samples) {
            val gain = dBToLinear(levelAt(points, i.toDouble() / SAMPLE_RATE))
            val attackRamp = if (i < attack) i.toDouble() / attack else 1.0
            out[i] *= inv * (gain * attackRamp).toFloat()
        }
        return out
    }

    private fun levelAt(points: List<Pair<Double, Double>>, seconds: Double): Double {
        if (seconds <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (toSeconds, toDb) = points[i]
            if (seconds <= toSeconds) {
                val (fromSeconds, fromDb) = points[i - 1]
                return fromDb + (toDb - fromDb) * (seconds - fromSeconds) / (toSeconds - fromSeconds)
            }
        }
        return points.last().second
    }

    private fun dBToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Longest run of frames the game treats as silence. */
    private fun longestUnheardRun(frames: List<Frame>, heard: List<Boolean>?): Int =
        longestRun(frames.indices.map { !(heard?.get(it) ?: frames[it].onPitch) }) *
                AudioConstants.FRAME_DURATION_MS.toInt()

    private fun longestRun(values: List<Boolean>): Int {
        var best = 0
        var current = 0
        for (value in values) {
            current = if (value) current + 1 else 0
            best = maxOf(best, current)
        }
        return best
    }

    /** Frame-by-frame view of the fixture, `.` on pitch and `x` off it, around the note boundary. */
    private fun pattern(frames: List<Frame>): String {
        val boundaryFrame = (NOTE_SECONDS * 1000 / AudioConstants.FRAME_DURATION_MS).toInt()
        val from = (boundaryFrame - 8).coerceAtLeast(0)
        val to = (boundaryFrame + 24).coerceAtMost(frames.size)
        return frames.subList(from, to).joinToString("") { if (it.onPitch) "." else "x" }
    }
}
