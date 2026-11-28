package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.midi.KeySignature
import net.exent.harmonisk.music.BowedAlgorithm
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs
import kotlin.random.Random

const val FREEBIRD_GAME_TIME_MS = 30_000L
const val FREEBIRD_NOTE_TRAVEL_MS = 4_000L
const val FREEBIRD_NOTE_INTERVAL_MS = 500L
const val FREEBIRD_PHRASE_GAP_MS = 2_000L
const val FREEBIRD_HIT_WINDOW_MS = 300f
const val FREEBIRD_PHRASES_PER_LEVEL = 5
const val FREEBIRD_MAX_LEVEL = 5
const val FREEBIRD_FINGERS = 5
const val FREEBIRD_HIT_CENTS = 75f
const val FREEBIRD_FEEDBACK_MS = 1_500L
const val FREEBIRD_NOTE_RADIUS = 0.040f
const val FREEBIRD_SPAWN_X = 1.08f
const val FREEBIRD_FADE_MS = 400L

val FreeBirdKey = KeySignature(0, false)
val FREEBIRD_HIT_WINDOW = FREEBIRD_HIT_WINDOW_MS * (FREEBIRD_SPAWN_X - BIRD_PERCH_X) / FREEBIRD_NOTE_TRAVEL_MS
val FREEBIRD_BEAK_LEAD_MS = FREEBIRD_NOTE_TRAVEL_MS *
    (FREEBIRD_SPAWN_X - (BIRD_BEAK_X + FREEBIRD_NOTE_RADIUS)) / (FREEBIRD_SPAWN_X - BIRD_PERCH_X)
val FREEBIRD_FADE_START_X = BIRD_BEAK_X - FREEBIRD_HIT_WINDOW + FREEBIRD_NOTE_RADIUS
val FREEBIRD_FADE_END_X = FREEBIRD_FADE_START_X -
    (FREEBIRD_SPAWN_X - BIRD_PERCH_X) * FREEBIRD_FADE_MS / FREEBIRD_NOTE_TRAVEL_MS

private val NATURAL_PITCH_CLASSES = intArrayOf(0, 2, 4, 5, 7, 9, 11)

data class FreeBirdNote(val midi: Int, val finger: Int)

data class FreeBirdHit(
    val id: Long,
    val midi: Int,
    val finger: Int,
    val createdAt: Long
)

data class FreeBirdLetter(
    val id: Long,
    val midi: Int,
    val finger: Int,
    val x: Float,
    val bonusMs: Long,
    val judged: Boolean = false
)

data class FreeBirdGameState(
    val score: Int = 0,
    val best: Int = 0,
    val timeMs: Long = FREEBIRD_GAME_TIME_MS,
    val level: Int = 1,
    val letters: List<FreeBirdLetter> = emptyList(),
    val playing: Boolean = false,
    val finished: Boolean = false,
    val feedback: FreeBirdHit? = null
)

data class FreeBirdSpelling(val naturalMidi: Int, val accidental: Int)

fun freeBirdPattern(level: Int): List<Int> {
    val top = (level - 1).coerceIn(0, FREEBIRD_FINGERS - 1)
    return (0 until top).toList() + (top downTo 0).toList()
}

fun freeBirdPhrase(instrument: Instrument, level: Int, random: Random = Random.Default): List<FreeBirdNote> {
    val open = instrument.tuning.random(random)
    return freeBirdPattern(level).mapNotNull { finger ->
        BowedAlgorithm.defaultFingerOffset(instrument, finger)?.let { FreeBirdNote(open + it, finger) }
    }
}

fun freeBirdSpelling(midi: Int, key: KeySignature): FreeBirdSpelling {
    val pitchClass = ((midi % 12) + 12) % 12
    for (naturalPitchClass in NATURAL_PITCH_CLASSES) {
        val alteration = KeySignature.keyAlteration(naturalPitchClass, key.fifths)
        if (((naturalPitchClass + alteration) % 12 + 12) % 12 == pitchClass) {
            return FreeBirdSpelling(midi - alteration, alteration)
        }
    }
    for (naturalPitchClass in NATURAL_PITCH_CLASSES) {
        if (naturalPitchClass == pitchClass) return FreeBirdSpelling(midi, 0)
    }
    return if (key.usesFlats) FreeBirdSpelling(midi + 1, -1) else FreeBirdSpelling(midi - 1, 1)
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

fun timeAfterHit(timeLeftMs: Long, bonusMs: Long, points: Int): Long =
    (timeLeftMs + bonusMs * points / 5).coerceAtMost(FREEBIRD_GAME_TIME_MS)

fun freeBirdInHitWindow(noteX: Float): Boolean {
    val leading = noteX - FREEBIRD_NOTE_RADIUS
    return leading <= BIRD_BEAK_X && leading >= BIRD_BEAK_X - FREEBIRD_HIT_WINDOW
}

fun freeBirdNoteAlpha(noteX: Float): Float =
    ((noteX - FREEBIRD_FADE_END_X) / (FREEBIRD_FADE_START_X - FREEBIRD_FADE_END_X)).coerceIn(0f, 1f)
