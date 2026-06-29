package net.exent.perfectpitch.music

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * Scoring engine for the Play by Melody mode.
 * Uses a 5-tier accuracy system with weighted pitch and duration scores.
 */
class ScoringEngine {

    companion object {
        private val PERFECT_CENTS = NoteUtils.PERFECT_CENTS
        private val GREAT_CENTS = NoteUtils.GREAT_CENTS
        private val GOOD_CENTS = NoteUtils.GOOD_CENTS
        private val OK_CENTS = NoteUtils.OK_CENTS

        // Duration scoring ratio thresholds
        const val PERFECT_DURATION_RATIO = 0.85f

        // Scoring weights — timing excluded (latency-agnostic)
        const val PITCH_WEIGHT = 0.6f
        const val DURATION_WEIGHT = 0.4f

        /**
         * Computes the running cumulative combined score across 5 levels.
         * For each level (5 down to 1), the best score at that level is capped
         * to the running max of higher levels, then summed.
         */
        fun computeCombinedScore(levelScores: List<Pair<Int, Float>>): Float {
            if (levelScores.isEmpty()) return 0f
            val bestPerLevel = levelScores.groupBy { it.first }.mapValues { (_, pairs) ->
                pairs.maxOf { it.second }
            }
            var runningMax = 0f
            var total = 0f
            for (level in 5 downTo 1) {
                val levelBest = bestPerLevel[level] ?: 0f
                if (levelBest > runningMax) runningMax = levelBest
                total += runningMax
            }
            return total
        }

        /**
         * Computes a time score (0–1) based on how long the user took relative
         * to the song's real duration.  Thresholds vary by level so that time
         * matters more at higher difficulties.
         *
         * @param ratio  totalWallTimeSeconds / songDurationSeconds  (≥ 0)
         * @param level  game level (1–5)
         * @return       0f–1f time-score multiplier
         */
        fun scoreTime(ratio: Double, level: Int): Float {
            if (level >= 4) return 1f
            val (fullRatio, zeroRatio) = when (level) {
                1 -> 3.0 to 5.0
                2 -> 2.0 to 4.0
                3 -> 1.1 to 3.0
                else -> return 1f
            }
            if (ratio <= fullRatio) return 1f
            if (ratio >= zeroRatio) return 0f
            return ((zeroRatio - ratio) / (zeroRatio - fullRatio)).toFloat()
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
            avgDurationScore = noteScores.map { it.durationScore }.average().toFloat(),
            encouragingMessage = getEncouragingMessage(avgScore)
        )
    }

    private fun getEncouragingMessage(score: Float): String = when (scoreTier(score)) {
        ScoreTier.LEGENDARY -> "Outstanding!"
        ScoreTier.EXCELLENT -> "Excellent!"
        ScoreTier.GREAT -> "Well done!"
        ScoreTier.GOOD -> "Keep it up!"
        ScoreTier.OK -> "Getting there!"
        ScoreTier.NEEDS_WORK -> "Keep practicing!"
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
    val wrongNoteCount: Int = 0,
    val encouragingMessage: String = ""
) {
    companion object {
        val EMPTY = MelodyScore(0f, 0, 0, 0, 0, 0, 0f, 0f, encouragingMessage = "Keep practicing!")
    }
}

data class ScoreBreakdown(
    val avgPitchScore: Float,
    val avgDurationScore: Float,
    val timeScore: Float
)

fun MelodyScore.toBreakdown(): ScoreBreakdown = ScoreBreakdown(
    avgPitchScore = avgPitchScore,
    avgDurationScore = avgDurationScore,
    timeScore = timeScore
)
