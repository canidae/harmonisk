package net.exent.perfectpitch.audio

import kotlin.math.roundToInt

/**
 * Tells a tone the player is still holding from what is left of a note they have released.
 *
 * A released note does not go quiet at once: the string, the body and the room carry on sounding,
 * and the detector keeps reporting the released pitch until the tail has fallen a long way below the
 * silence threshold. Nothing in the pitch stream then says the player stopped, so a pause between two
 * notes of the same pitch goes unnoticed and one tone is counted as two notes.
 *
 * The level says it. A note being held stays at its own level, while releasing one takes the level
 * away quickly — on a Pixel 7 Pro, through the device's own speaker and microphone, a held tone stayed
 * within 0.3 dB of its level for seconds at a time, while a released note fell 8.4 dB per 60 ms, and
 * the detector went on reporting the released pitch for a further 263 ms. So the gate watches how far
 * the level has moved since a moment ago, which follows how loud the player plays and how much gain
 * the microphone applies instead of depending on a fixed sensitivity.
 *
 * A drop is only a release if the level is also down on the tone's own level. A metronome click, a
 * cough or a chair lift the level for a moment and drop it again: a fall measured against the last
 * moment would read that as a note ending, and the gate would then be shut over a tone that never
 * stopped. Measured against the tone's own level, which barely moves for a sound that short, the
 * click is plainly not the note going away.
 *
 * Having closed, the gate reopens for a new attack: the level rising like a fresh note, or coming back
 * to the tone's own level. A player who takes 10 dB off their playing without stopping is read as
 * having stopped, which is the safe way round: the game asks for a fresh attack rather than crediting
 * a note nobody played.
 *
 * Time advances one audio hop per call. The gate is driven by the audio stream rather than the CPU
 * clock, so replaying recorded audio behaves exactly like listening to the microphone.
 */
class SoundingToneGate(
    private val fastDropDb: Double = FAST_DROP_DB,
    private val slowDropDb: Double = SLOW_DROP_DB,
    private val releaseFloorDb: Double = RELEASE_FLOOR_DB,
    private val resumeRiseDb: Double = RESUME_RISE_DB,
    private val resumeMarginDb: Double = RESUME_MARGIN_DB,
    private val noteLevelMs: Double = NOTE_LEVEL_MS
) {
    companion object {
        /**
         * Drop within [FAST_DROP_MS] that means the player released the note. Below the 8.4 dB per
         * 60 ms a real release measured, and well above what a held note moves: vibrato 3 dB deep
         * moves the level around 2 dB over this span.
         */
        const val FAST_DROP_DB = 8.0

        /** Span the fast drop is measured over. */
        const val FAST_DROP_MS = 80.0

        /**
         * Drop within [SLOW_DROP_MS] that means the note has faded away. Catches a note that was
         * eased off rather than lifted, while sitting above the few dB a ring-out loses over a
         * quarter of a second.
         */
        const val SLOW_DROP_DB = 10.0

        /** Span the slow drop is measured over. */
        const val SLOW_DROP_MS = 250.0

        /**
         * How far below the tone's own level a drop has to go to be a release. Enough that a level
         * lifted for a moment by a click and dropped again is not mistaken for the note ending.
         */
        const val RELEASE_FLOOR_DB = 4.0

        /**
         * Rise within [FAST_DROP_MS] that counts as the player taking the note up again. Less than
         * [FAST_DROP_DB], so the gate is quicker to hear a new note than to give up on one.
         */
        const val RESUME_RISE_DB = 6.0

        /** How close to the tone's own level the level has to come back to count as still sounding. */
        const val RESUME_MARGIN_DB = 3.0

        /**
         * Time constant of the tone's own level. Long, so that a click, a cough or a metronome beat
         * barely shifts the level a drop is judged against.
         */
        const val NOTE_LEVEL_MS = 500.0

        /** Time one call advances, i.e. the capture hop. */
        private const val HOP_MS =
            AudioConstants.BUFFER_SIZE_SAMPLES * 1000.0 / AudioConstants.SAMPLE_RATE

        private val FAST_HOPS = (FAST_DROP_MS / HOP_MS).roundToInt()

        private val SLOW_HOPS = (SLOW_DROP_MS / HOP_MS).roundToInt()

        /** Levels kept: enough to reach back [SLOW_HOPS] frames. */
        private val HISTORY = SLOW_HOPS + 1
    }

    /** Levels of recent frames, oldest at [next]. Frames with no level are [Double.NaN]. */
    private val levels = DoubleArray(HISTORY) { Double.NaN }

    /** Where the next level is written. */
    private var next = 0

    /** True while only what is left of a released note is being heard. */
    private var released = false

    /** Level the tone is sounding at, or [Double.NaN] before one has been heard. */
    private var noteLevelDb = Double.NaN

    /**
     * Whether the frame heard at [levelDb] still carries a tone the player is holding.
     *
     * [levelDb] is null when there is no level to judge by: silence, a frame the detector could not
     * make a pitch of, or a source with no level at all such as MIDI. A level separating two such
     * frames is what follows a silence, so it is heard as a new note rather than a drop.
     */
    fun isSounding(levelDb: Double?): Boolean {
        val earlierFast = level(FAST_HOPS)
        val earlierSlow = level(SLOW_HOPS)
        record(levelDb)

        if (levelDb == null) return false

        if (released) {
            val backToTheNote = !noteLevelDb.isNaN() && levelDb >= noteLevelDb - resumeMarginDb
            val risenLikeAnAttack = earlierFast.isNaN() || levelDb >= earlierFast + resumeRiseDb
            if (backToTheNote || risenLikeAnAttack) released = false
        } else {
            val fellFast = !earlierFast.isNaN() && levelDb <= earlierFast - fastDropDb
            val fadedAway = !earlierSlow.isNaN() && levelDb <= earlierSlow - slowDropDb
            val downOnTheNote = !noteLevelDb.isNaN() && levelDb <= noteLevelDb - releaseFloorDb
            if ((fellFast || fadedAway) && downOnTheNote) released = true
        }

        if (!released) {
            noteLevelDb = if (noteLevelDb.isNaN()) levelDb
            else noteLevelDb + (levelDb - noteLevelDb) * HOP_MS / noteLevelMs
        }
        return !released
    }

    /** The level [framesAgo] frames before the one being judged (1 = the previous frame). */
    private fun level(framesAgo: Int): Double =
        levels[((next - framesAgo) % HISTORY + HISTORY) % HISTORY]

    private fun record(levelDb: Double?) {
        levels[next] = levelDb ?: Double.NaN
        next = (next + 1) % HISTORY
    }
}
