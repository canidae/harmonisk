package com.perfectpitch.music

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * Scoring engine for the Play by Melody mode.
 * Evaluates pitch accuracy, duration accuracy, and timing.
 */
class ScoringEngine {

    companion object {
        // Pitch scoring thresholds (cents)
        const val PERFECT_CENTS = 10f
        const val GOOD_CENTS = 25f
        const val FAIR_CENTS = 50f

        // Points for each accuracy level
        const val PERFECT_POINTS = 100
        const val GOOD_POINTS = 75
        const val FAIR_POINTS = 50
        const val MISS_POINTS = 0

        // Scoring weights
        const val PITCH_WEIGHT = 0.4f
        const val DURATION_WEIGHT = 0.4f
        const val TIMING_WEIGHT = 0.2f
    }

    /**
     * Score a single note performance.
     */
    fun scoreNote(result: NotePerformance): NoteScore {
        val pitchScore = scorePitch(result.centsOff)
        val durationScore = scoreDuration(result.actualDurationMs, result.expectedDurationMs)
        val timingScore = scoreTiming(result.timingOffsetMs, result.expectedDurationMs)

        val overallScore = (
            pitchScore * PITCH_WEIGHT +
            durationScore * DURATION_WEIGHT +
            timingScore * TIMING_WEIGHT
        )

        return NoteScore(
            pitchScore = pitchScore,
            durationScore = durationScore,
            timingScore = timingScore,
            overallScore = overallScore,
            pitchAccuracy = getPitchAccuracy(result.centsOff)
        )
    }

    /**
     * Score pitch accuracy based on cents offset.
     */
    private fun scorePitch(centsOff: Float): Float {
        val absCents = abs(centsOff)
        return when {
            absCents <= PERFECT_CENTS -> PERFECT_POINTS.toFloat()
            absCents <= GOOD_CENTS -> GOOD_POINTS.toFloat()
            absCents <= FAIR_CENTS -> FAIR_POINTS.toFloat()
            else -> MISS_POINTS.toFloat()
        }
    }

    private fun getPitchAccuracy(centsOff: Float): PitchAccuracy {
        val absCents = abs(centsOff)
        return when {
            absCents <= PERFECT_CENTS -> PitchAccuracy.PERFECT
            absCents <= GOOD_CENTS -> PitchAccuracy.GOOD
            absCents <= FAIR_CENTS -> PitchAccuracy.FAIR
            else -> PitchAccuracy.MISS
        }
    }

    /**
     * Score duration accuracy as a ratio of actual vs expected.
     */
    private fun scoreDuration(actualMs: Long, expectedMs: Long): Float {
        if (expectedMs <= 0) return 100f
        val ratio = min(actualMs, expectedMs).toFloat() / max(actualMs, expectedMs).toFloat()
        return ratio * 100f
    }

    /**
     * Score timing (when the note started relative to when it should have).
     */
    private fun scoreTiming(offsetMs: Long, expectedDurationMs: Long): Float {
        if (expectedDurationMs <= 0) return 100f
        val tolerance = expectedDurationMs * 0.25f // 25% of note duration as timing window
        val absOffset = abs(offsetMs).toFloat()
        return when {
            absOffset <= tolerance * 0.5f -> 100f
            absOffset <= tolerance -> 75f
            absOffset <= tolerance * 2f -> 50f
            else -> 25f
        }
    }

    /**
     * Calculate the overall melody score from individual note scores.
     */
    fun calculateMelodyScore(noteScores: List<NoteScore>): MelodyScore {
        if (noteScores.isEmpty()) return MelodyScore.EMPTY

        val avgScore = noteScores.map { it.overallScore }.average().toFloat()
        val notesHit = noteScores.count { it.pitchAccuracy != PitchAccuracy.MISS }
        val perfectCount = noteScores.count { it.pitchAccuracy == PitchAccuracy.PERFECT }

        // Calculate longest streak
        var currentStreak = 0
        var longestStreak = 0
        for (score in noteScores) {
            if (score.pitchAccuracy == PitchAccuracy.PERFECT || score.pitchAccuracy == PitchAccuracy.GOOD) {
                currentStreak++
                longestStreak = max(longestStreak, currentStreak)
            } else {
                currentStreak = 0
            }
        }

        return MelodyScore(
            overallScore = avgScore,
            grade = scoreToGrade(avgScore),
            totalNotes = noteScores.size,
            notesHit = notesHit,
            perfectNotes = perfectCount,
            longestStreak = longestStreak,
            avgPitchScore = noteScores.map { it.pitchScore }.average().toFloat(),
            avgDurationScore = noteScores.map { it.durationScore }.average().toFloat()
        )
    }

    private fun scoreToGrade(score: Float): LetterGrade {
        return when {
            score >= 95f -> LetterGrade.S
            score >= 85f -> LetterGrade.A
            score >= 75f -> LetterGrade.B
            score >= 60f -> LetterGrade.C
            score >= 40f -> LetterGrade.D
            else -> LetterGrade.F
        }
    }
}

data class NotePerformance(
    val expectedMidiNote: Int,
    val detectedMidiNote: Int,
    val centsOff: Float,
    val expectedDurationMs: Long,
    val actualDurationMs: Long,
    val timingOffsetMs: Long
)

data class NoteScore(
    val pitchScore: Float,
    val durationScore: Float,
    val timingScore: Float,
    val overallScore: Float,
    val pitchAccuracy: PitchAccuracy
)

enum class PitchAccuracy { PERFECT, GOOD, FAIR, MISS }

enum class LetterGrade { S, A, B, C, D, F }

data class MelodyScore(
    val overallScore: Float,
    val grade: LetterGrade,
    val totalNotes: Int,
    val notesHit: Int,
    val perfectNotes: Int,
    val longestStreak: Int,
    val avgPitchScore: Float,
    val avgDurationScore: Float
) {
    companion object {
        val EMPTY = MelodyScore(0f, LetterGrade.F, 0, 0, 0, 0, 0f, 0f)
    }
}
