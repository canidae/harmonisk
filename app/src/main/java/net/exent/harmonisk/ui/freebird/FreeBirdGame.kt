package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.music.BowedAlgorithm
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs

const val FREEBIRD_GAME_TIME_MS = 30_000L
const val FREEBIRD_LEVEL_TIME_MS = 30_000L
const val FREEBIRD_NOTE_TRAVEL_MS = 8_000L
const val FREEBIRD_SPAWN_MAX_MS = 4_000L
const val FREEBIRD_SPAWN_MIN_MS = 1_000L
const val FREEBIRD_TIME_BONUS_MS = 2_000L
const val FREEBIRD_LEVEL_PAUSE_MS = 1_200L
const val FREEBIRD_MAX_LEVEL = 6
const val FREEBIRD_HIT_CENTS = 75f
const val FREEBIRD_FEEDBACK_MS = 1_500L

data class FreeBirdNote(val midi: Int, val finger: Int)

enum class FreeBirdMissReason { SILENT, WRONG_NOTE, SHARP, FLAT }

sealed interface FreeBirdFeedback {
    val id: Long

    data class Hit(
        override val id: Long,
        val points: Int,
        val midi: Int,
        val finger: Int,
        val createdAt: Long
    ) : FreeBirdFeedback

    data class Miss(
        override val id: Long,
        val reason: FreeBirdMissReason,
        val cents: Int? = null,
        val detectedMidi: Int? = null
    ) : FreeBirdFeedback
}

data class FreeBirdLetter(
    val id: Long,
    val midi: Int,
    val finger: Int,
    val x: Float,
    val judged: Boolean = false
)

data class FreeBirdGameState(
    val score: Int = 0,
    val best: Int = 0,
    val timeMs: Long = FREEBIRD_GAME_TIME_MS,
    val level: Int = 1,
    val levelElapsedMs: Long = 0,
    val letters: List<FreeBirdLetter> = emptyList(),
    val playing: Boolean = false,
    val finished: Boolean = false,
    val levelUp: Int? = null,
    val feedback: FreeBirdFeedback? = null
)

fun notesForLevel(instrument: Instrument, level: Int): List<FreeBirdNote> {
    val fingers = if (level >= FREEBIRD_MAX_LEVEL) 0..4 else listOf((level - 1).coerceIn(0, 4))
    val notes = linkedMapOf<Int, Int>()
    for (finger in fingers) {
        val offset = BowedAlgorithm.defaultFingerOffset(instrument, finger) ?: continue
        for (open in instrument.tuning) {
            notes.putIfAbsent(open + offset, finger)
        }
    }
    return notes.map { FreeBirdNote(it.key, it.value) }.sortedBy { it.midi }
}

fun centsFromTarget(pitch: PitchResult, targetMidi: Int): Float =
    (pitch.midiNoteNumber - targetMidi) * 100f + pitch.centsOff

fun pointsForCents(cents: Float): Int = when (val distance = abs(cents)) {
    in 0f..10f -> 5
    in 10f..25f -> 4
    in 25f..40f -> 3
    in 40f..55f -> 2
    in 55f..FREEBIRD_HIT_CENTS -> 1
    else -> 0
}
