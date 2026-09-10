package net.exent.perfectpitch.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * What the level gate makes of a note's level over time: which shapes are a player holding a note,
 * and which are the tail of one they have released. Frame sequences here stand in for the levels the
 * detector reports, so the shapes can be made as awkward as needed.
 */
class SoundingToneGateTest {

    private companion object {
        private val HOP_MS =
            AudioConstants.BUFFER_SIZE_SAMPLES * 1000.0 / AudioConstants.SAMPLE_RATE

        /** A held note at a comfortable level. */
        private const val HELD_DB = -9.0

        /** What is left of a released note once it has fallen away. */
        private const val TAIL_DB = -25.0

        /** Vibrato depth of a well played sustained note, in dB either way. */
        private const val VIBRATO_DB = 3.0

        private const val VIBRATO_HZ = 5.5

        /** How far above the note a click, a cough or a chair lifts the level. */
        private const val CLICK_DB = 12.0
    }

    @Test
    fun aSteadyToneKeepsSounding() {
        val gate = SoundingToneGate()
        assertTrue(held(gate, HELD_DB, 3000.0).all { it })
    }

    @Test
    fun vibratoDoesNotReadAsARelease() {
        val gate = SoundingToneGate()
        val frames = held(gate, HELD_DB, 3000.0) { index ->
            HELD_DB + VIBRATO_DB * sin(2 * PI * VIBRATO_HZ * index * HOP_MS / 1000.0)
        }
        assertTrue("a note played with vibrato is still one note", frames.all { it })
    }

    @Test
    fun aReleasedNoteStopsSounding() {
        val gate = SoundingToneGate()
        val played = held(gate, HELD_DB, 500.0)
        val tail = held(gate, TAIL_DB, 500.0)
        assertTrue(played.all { it })
        assertFalse("the tail of a released note is not the note", tail.any { it })
    }

    @Test
    fun aFadingTailDoesNotReopenTheGate() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        // A tail that keeps falling, as a room does after a note stops.
        val tail = held(gate, TAIL_DB, 800.0) { index ->
            TAIL_DB - 20.0 * index * HOP_MS / 800.0
        }
        assertFalse(tail.any { it })
    }

    @Test
    fun aReleaseOfOnlyTwelveDecibelsIsHeard() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        // Eased off rather than lifted: the tail is still 12 dB below the note, well above silence.
        val fading = held(gate, HELD_DB, 80.0) { index ->
            HELD_DB - 12.0 * index / (80.0 / HOP_MS)
        }
        val tail = held(gate, HELD_DB - 12.0, 500.0)
        assertTrue(fading.first())
        assertFalse("a note left ringing 12 dB down has still been released", tail.any { it })
    }

    @Test
    fun playingMuchMoreQuietlyWithoutStoppingReadsAsARelease() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        // 16 dB off the level with no pause at all: the safe way round is to ask for a fresh attack.
        val quieter = held(gate, HELD_DB - 16.0, 500.0)
        assertFalse(quieter.any { it })
    }

    @Test
    fun aLevelWobbleIsNotARelease() {
        val gate = SoundingToneGate()
        val frames = held(gate, HELD_DB, 2000.0) { index ->
            HELD_DB + 1.5 * sin(2 * PI * VIBRATO_HZ * index * HOP_MS / 1000.0)
        }
        assertTrue(frames.all { it })
    }

    @Test
    fun aNewAttackReopensTheGate() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        val tail = held(gate, TAIL_DB, 300.0)
        val repeat = held(gate, HELD_DB, 200.0)
        assertFalse(tail.any { it })
        assertTrue("playing the note again must be heard", repeat.all { it })
    }

    @Test
    fun anAttackQuieterThanTheFirstNoteReopensTheGate() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        held(gate, TAIL_DB, 300.0)
        val repeat = held(gate, TAIL_DB + SoundingToneGate.RESUME_RISE_DB + 1.0, 200.0)
        assertTrue(repeat.all { it })
    }

    @Test
    fun aSlowDecayIsStillTheSameNote() {
        val gate = SoundingToneGate()
        // A note that fades away over seconds, at a rate the gate's reference keeps up with.
        val frames = held(gate, HELD_DB, 2000.0) { index ->
            HELD_DB - 10.0 * index * HOP_MS / 1000.0
        }
        assertTrue("fading is not releasing", frames.all { it })
    }

    @Test
    fun aStruckNoteThatDecaysAwayIsReleased() {
        val gate = SoundingToneGate()
        // A plucked or struck note: loud attack, then away quickly. The note ends when its level does.
        val frames = held(gate, HELD_DB, 500.0) { index ->
            HELD_DB - 80.0 * index * HOP_MS / 1000.0
        }
        assertTrue(frames.first())
        assertFalse("a note that has faded away is not still being held", frames.last())
    }

    @Test
    fun aQuietNoteAfterASilenceIsMeasuredAgainstItself() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        held(gate, null, 500.0)
        val quiet = held(gate, -30.0, 300.0)
        assertTrue("a quiet note after a silence must be heard", quiet.all { it })
    }

    @Test
    fun aLoudSoundOverAHeldNoteIsNotARelease() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        // A metronome click, a cough or a chair: loud over the note, then gone again. Falling off the
        // click looks like a release, so the gate has to hear the note again as soon as the click is
        // over rather than staying shut over a tone that never stopped.
        held(gate, HELD_DB + CLICK_DB, 3 * HOP_MS)
        val after = held(gate, HELD_DB, 500.0)
        assertTrue(
            "a tone that never stopped must go on being heard straight through a click, " +
                    "not fall silent for ${after.count { !it }} frames",
            after.all { it }
        )
    }

    @Test
    fun aShortDropoutIsNotARelease() {
        val gate = SoundingToneGate()
        held(gate, HELD_DB, 500.0)
        held(gate, null, 3 * HOP_MS)
        val rest = held(gate, HELD_DB, 200.0)
        assertTrue("frames the detector drops are not a note ending", rest.all { it })
    }

    @Test
    fun framesWithoutALevelAreNeverSounding() {
        val gate = SoundingToneGate()
        assertFalse(gate.isSounding(null))
        held(gate, HELD_DB, 200.0)
        assertFalse(gate.isSounding(null))
    }

    /** Feeds a tone at [levelDb] for [ms], taking one audio hop per frame. */
    private fun held(
        gate: SoundingToneGate,
        levelDb: Double?,
        ms: Double,
        level: (Int) -> Double? = { levelDb }
    ): List<Boolean> =
        (0 until (ms / HOP_MS).toInt()).map { gate.isSounding(level(it)) }
}
