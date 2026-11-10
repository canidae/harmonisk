package net.exent.harmonisk.music

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class ScoringEngine {

    companion object {
        private val PERFECT_CENTS = NoteUtils.PERFECT_CENTS
        private val GREAT_CENTS = NoteUtils.GREAT_CENTS
        private val GOOD_CENTS = NoteUtils.GOOD_CENTS
        private val OK_CENTS = NoteUtils.OK_CENTS

        const val PERFECT_DURATION_RATIO = 0.85f

        const val LEVEL_UP_SCORE = 90f

        fun unlockedLevelAfterRun(currentLevel: Int, unlockedLevel: Int, score: Float): Int {
            val current = currentLevel.coerceIn(1, 5)
            val unlocked = unlockedLevel.coerceIn(current, 5)
            return if (score >= LEVEL_UP_SCORE && current == unlocked) (current + 1).coerceAtMost(5)
            else unlocked
        }

        const val TIME_GRACE_RATIO_LEVEL_1 = 2.00
        const val TIME_GRACE_RATIO_LEVEL_2 = 1.20
        const val TIME_GRACE_RATIO_LEVEL_3 = 1.05

        fun timeGraceRatio(level: Int): Double = when (level) {
            1 -> TIME_GRACE_RATIO_LEVEL_1
            2 -> TIME_GRACE_RATIO_LEVEL_2
            else -> TIME_GRACE_RATIO_LEVEL_3
        }

        const val PITCH_WEIGHT = 0.6f
        const val DURATION_WEIGHT = 0.4f

        fun bestScoreForLevel(levelScores: List<Pair<Int, Float>>, level: Int): Float =
            levelScores.filter { it.first == level }.maxOfOrNull { it.second } ?: 0f

        fun combineScores(noteScore: Float, timeScore: Float, wrongNoteScore: Float, level: Int): Float {
            var total = noteScore
            var categories = 1
            if (level <= 3) {
                total += timeScore * 100f
                categories++
            }
            if (level >= 3) {
                total += wrongNoteScore
                categories++
            }
            return total / categories
        }

        fun scoreTime(ratio: Double, level: Int): Float {
            if (level >= 4) return 1f
            if (ratio <= 0.0) return 1f
            return min(1.0, timeGraceRatio(level) / ratio).toFloat()
        }

        fun scoreTier(score: Float): ScoreTier = when {
            score >= 95f -> ScoreTier.LEGENDARY
            score >= 80f -> ScoreTier.EXCELLENT
            score >= 65f -> ScoreTier.GREAT
            score >= 50f -> ScoreTier.GOOD
            score >= 30f -> ScoreTier.OK
            else -> ScoreTier.NEEDS_WORK
        }
    }

    fun scoreNote(result: NotePerformance): NoteScore {
        val pitchAccuracy = getPitchAccuracy(result.centsOff)
        val durationScore = scoreDuration(result.actualDurationMs, result.expectedDurationMs)
        val overallScore = (
            pitchAccuracy.score * PITCH_WEIGHT +
            durationScore * DURATION_WEIGHT
        )
        val combinedAccuracy = getCombinedAccuracy(overallScore)

        return NoteScore(
            pitchScore = pitchAccuracy.score,
            durationScore = durationScore,
            overallScore = overallScore,
            pitchAccuracy = combinedAccuracy
        )
    }

    private fun getPitchAccuracy(centsOff: Float): PitchAccuracy {
        val absCents = abs(centsOff)
        return when {
            absCents <= PERFECT_CENTS -> PitchAccuracy.PERFECT
            absCents <= GREAT_CENTS -> PitchAccuracy.GREAT
            absCents <= GOOD_CENTS -> PitchAccuracy.GOOD
            absCents <= OK_CENTS -> PitchAccuracy.OK
            else -> PitchAccuracy.MISS
        }
    }

    private fun getCombinedAccuracy(overallScore: Float): PitchAccuracy = when {
        overallScore >= 95f -> PitchAccuracy.PERFECT
        overallScore >= 80f -> PitchAccuracy.GREAT
        overallScore >= 65f -> PitchAccuracy.GOOD
        overallScore >= 50f -> PitchAccuracy.OK
        else -> PitchAccuracy.MISS
    }

    fun scoreDuration(actualMs: Long, expectedMs: Long): Float {
        if (expectedMs <= 0) return 100f
        val ratio = min(actualMs, expectedMs).toFloat() / max(actualMs, expectedMs).toFloat()
        return if (ratio >= PERFECT_DURATION_RATIO) 100f else ratio * 100f
    }

    fun calculateMelodyScore(noteScores: List<NoteScore>): MelodyScore {
        if (noteScores.isEmpty()) return MelodyScore.EMPTY

        var currentStreak = 0
        var longestStreak = 0

        for (score in noteScores) {
            if (score.pitchAccuracy == PitchAccuracy.PERFECT || score.pitchAccuracy == PitchAccuracy.GREAT) {
                currentStreak++
            } else {
                longestStreak = max(longestStreak, currentStreak)
                currentStreak = 0
            }
        }
        longestStreak = max(longestStreak, currentStreak)

        val avgScore = noteScores.map { it.overallScore }.average().toFloat()
        val notesHit = noteScores.count { it.pitchAccuracy != PitchAccuracy.MISS }
        val perfectCount = noteScores.count { it.pitchAccuracy == PitchAccuracy.PERFECT }
        val greatCount = noteScores.count { it.pitchAccuracy == PitchAccuracy.GREAT }

        return MelodyScore(
            overallScore = avgScore,
            totalNotes = noteScores.size,
            notesHit = notesHit,
            perfectNotes = perfectCount,
            greatNotes = greatCount,
            longestStreak = longestStreak,
            avgPitchScore = noteScores.map { it.pitchScore }.average().toFloat(),
            avgDurationScore = noteScores.map { it.durationScore }.average().toFloat()
        )
    }
}

data class NotePerformance(
    val expectedMidiNote: Int,
    val detectedMidiNote: Int,
    val centsOff: Float,
    val expectedDurationMs: Long,
    val actualDurationMs: Long
)

data class NoteScore(
    val pitchScore: Float,
    val durationScore: Float,
    val overallScore: Float,
    val pitchAccuracy: PitchAccuracy
)

enum class PitchAccuracy(val score: Float) {
    PERFECT(100f), GREAT(80f), GOOD(50f), OK(25f), MISS(0f)
}

enum class ScoreTier { LEGENDARY, EXCELLENT, GREAT, GOOD, OK, NEEDS_WORK }

data class MelodyScore(
    val overallScore: Float,
    val totalNotes: Int,
    val notesHit: Int,
    val perfectNotes: Int,
    val greatNotes: Int,
    val longestStreak: Int,
    val avgPitchScore: Float,
    val avgDurationScore: Float,
    val timeScore: Float = 1.0f,
    val wrongNoteScore: Float = 100f,
    val wrongNoteCount: Int = 0
) {
    companion object {
        val EMPTY = MelodyScore(0f, 0, 0, 0, 0, 0, 0f, 0f)
    }
}
