package net.exent.harmonisk.ui.freebird

import net.exent.harmonisk.midi.KeySignature
import net.exent.harmonisk.music.BowedAlgorithm
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

const val FREEBIRD_GAME_TIME_MS = 30_000L
const val FREEBIRD_NOTE_TRAVEL_MS = 4_000L
const val FREEBIRD_NOTE_INTERVAL_MS = 500L
const val FREEBIRD_PHRASE_GAP_MS = 2_000L
const val FREEBIRD_HIT_WINDOW_MS = 300f
const val FREEBIRD_PHRASES_PER_LEVEL = 5
const val FREEBIRD_MAX_LEVEL = 7
const val FREEBIRD_FINGERS = 5
const val FREEBIRD_HIT_CENTS = 75f
const val FREEBIRD_FEEDBACK_MS = 1_500L
const val FREEBIRD_NOTE_RADIUS = 0.040f
const val FREEBIRD_SPAWN_X = 1.08f
const val FREEBIRD_FADE_MS = 400L
const val FREEBIRD_BEATS_PER_BAR = 4
const val FREEBIRD_PHRASE_GAP_BEATS = 4
const val FREEBIRD_COUNT_IN_BARS = 2
const val FREEBIRD_COUNT_IN_BEATS = FREEBIRD_COUNT_IN_BARS * FREEBIRD_BEATS_PER_BAR
const val FREEBIRD_BEAT_MUTE_MS = 180L
const val FREEBIRD_BAR_MS = FREEBIRD_BEATS_PER_BAR * FREEBIRD_NOTE_INTERVAL_MS
const val FREEBIRD_BPM_STEP = 10f
const val FREEBIRD_METERS_PER_SECOND = 4f
const val FREEBIRD_METERS_PER_MS = FREEBIRD_METERS_PER_SECOND / 1_000f

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
    val distanceMeters: Float = 0f,
    val bestTenths: Int = 0,
    val recordTenths: Int = 0,
    val newRecord: Boolean = false,
    val timeMs: Long = FREEBIRD_GAME_TIME_MS,
    val level: Int = 1,
    val letters: List<FreeBirdLetter> = emptyList(),
    val playing: Boolean = false,
    val finished: Boolean = false,
    val feedback: FreeBirdHit? = null,
    val beatAt: Long = 0L,
    val beatAccent: Boolean = false
)

data class FreeBirdSpelling(val naturalMidi: Int, val accidental: Int)

data class FreeBirdFingering(val string: Int, val finger: Int)

fun freeBirdFingerings(level: Int): List<FreeBirdFingering> = when {
    level <= FREEBIRD_MAX_LEVEL - 2 -> {
        val top = (level - 1).coerceIn(0, FREEBIRD_FINGERS - 1)
        ((0 until top).toList() + (top downTo 0).toList()).map { FreeBirdFingering(0, it) }
    }
    level == FREEBIRD_MAX_LEVEL - 1 -> {
        val lower = (0 until 4).map { FreeBirdFingering(0, it) }
        val upperUp = (0..4).map { FreeBirdFingering(1, it) }
        val upperDown = (4 downTo 0).map { FreeBirdFingering(1, it) }
        val lowerDown = (3 downTo 0).map { FreeBirdFingering(0, it) }
        lower + upperUp + upperDown + lowerDown
    }
    else -> {
        val up = buildList {
            repeat(3) { string -> (0 until 4).forEach { add(FreeBirdFingering(string, it)) } }
            (0..4).forEach { add(FreeBirdFingering(3, it)) }
        }
        val down = buildList {
            (4 downTo 0).forEach { add(FreeBirdFingering(3, it)) }
            repeat(3) { index -> (3 downTo 0).forEach { add(FreeBirdFingering(2 - index, it)) } }
        }
        up + down
    }
}

fun freeBirdPhrase(instrument: Instrument, level: Int, random: Random = Random.Default): List<FreeBirdNote> {
    val first = when (level) {
        FREEBIRD_MAX_LEVEL -> 0
        FREEBIRD_MAX_LEVEL - 1 -> random.nextInt(instrument.tuning.size - 1)
        else -> random.nextInt(instrument.tuning.size)
    }
    return freeBirdFingerings(level).mapNotNull { fingering ->
        BowedAlgorithm.defaultFingerOffset(instrument, fingering.finger)?.let { offset ->
            FreeBirdNote(instrument.tuning[first + fingering.string] + offset, fingering.finger)
        }
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

fun freeBirdFirstNoteBeatMs(intervalMs: Long = FREEBIRD_NOTE_INTERVAL_MS): Long =
    intervalMs + FREEBIRD_BEAK_LEAD_MS.toLong()

fun freeBirdFirstBeatMs(intervalMs: Long = FREEBIRD_NOTE_INTERVAL_MS): Long =
    freeBirdFirstNoteBeatMs(intervalMs) - FREEBIRD_COUNT_IN_BEATS * intervalMs

fun freeBirdNextPhraseBeatMs(
    lastNoteBeatMs: Long,
    phraseFirstBeatMs: Long,
    intervalMs: Long = FREEBIRD_NOTE_INTERVAL_MS
): Long {
    val barMs = intervalMs * FREEBIRD_BEATS_PER_BAR
    val earliest = lastNoteBeatMs + intervalMs * FREEBIRD_PHRASE_GAP_BEATS
    val bars = (earliest - phraseFirstBeatMs + barMs - 1) / barMs
    return phraseFirstBeatMs + bars * barMs
}

fun freeBirdTempoChangeBeatMs(lastNoteBeatMs: Long, phraseFirstBeatMs: Long, intervalMs: Long): Long {
    val beats = (lastNoteBeatMs - phraseFirstBeatMs) / intervalMs
    val downbeat = (beats / FREEBIRD_BEATS_PER_BAR + 1) * FREEBIRD_BEATS_PER_BAR
    return phraseFirstBeatMs + downbeat * intervalMs
}

fun freeBirdFasterInterval(intervalMs: Long): Long {
    val bpm = 60_000.0 / intervalMs + FREEBIRD_BPM_STEP
    return (60_000.0 / bpm).roundToLong().coerceAtLeast(1L)
}

fun freeBirdInHitWindow(noteX: Float): Boolean {
    val leading = noteX - FREEBIRD_NOTE_RADIUS
    return leading <= BIRD_BEAK_X && leading >= BIRD_BEAK_X - FREEBIRD_HIT_WINDOW
}

fun freeBirdNoteAlpha(noteX: Float): Float =
    ((noteX - FREEBIRD_FADE_END_X) / (FREEBIRD_FADE_START_X - FREEBIRD_FADE_END_X)).coerceIn(0f, 1f)

fun freeBirdDistanceMeters(elapsedMs: Long): Float = elapsedMs * FREEBIRD_METERS_PER_MS

fun freeBirdFlightTimeMs(elapsedMs: Long, timeLeftMs: Long): Long =
    elapsedMs + timeLeftMs.coerceAtMost(0L)

fun freeBirdBestLineX(width: Float, recordTenths: Int, distanceMeters: Float): Float {
    val pixelsPerMeter = (FREEBIRD_SPAWN_X - BIRD_PERCH_X) * width /
        (FREEBIRD_NOTE_TRAVEL_MS / 1_000f * FREEBIRD_METERS_PER_SECOND)
    return width * BIRD_PERCH_X + (recordTenths / 10f - distanceMeters) * pixelsPerMeter
}
