package com.perfectpitch.music

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * Scoring engine for the Play by Melody mode.
 * Uses a 5-tier accuracy system with weighted pitch and duration scores.
 */
class ScoringEngine {

    companion object {
        // Pitch scoring thresholds (cents)
        const val PERFECT_CENTS = 10f
        const val GREAT_CENTS = 20f
        const val GOOD_CENTS = 30f
        const val OK_CENTS = 40f

        // Point values per tier
        const val PERFECT_POINTS = 100
        const val GREAT_POINTS = 80
        const val GOOD_POINTS = 50
        const val OK_POINTS = 25
        const val MISS_POINTS = 0

        // Scoring weights — timing excluded (latency-agnostic)
        const val PITCH_WEIGHT = 0.6f
        const val DURATION_WEIGHT = 0.4f
    }

    fun scoreNote(result: NotePerformance): NoteScore {
        val pitchScore = scorePitch(result.centsOff)
        val durationScore = scoreDuration(result.actualDurationMs, result.expectedDurationMs)

        val overallScore = (
            pitchScore * PITCH_WEIGHT +
            durationScore * DURATION_WEIGHT
        )

        return NoteScore(
            pitchScore = pitchScore,
            durationScore = durationScore,
            overallScore = overallScore,
            pitchAccuracy = getPitchAccuracy(result.centsOff)
        )
    }

    private fun scorePitch(centsOff: Float): Float {
        val absCents = abs(centsOff)
        return when {
            absCents <= PERFECT_CENTS -> PERFECT_POINTS.toFloat()
            absCents <= GREAT_CENTS -> GREAT_POINTS.toFloat()
            absCents <= GOOD_CENTS -> GOOD_POINTS.toFloat()
            absCents <= OK_CENTS -> OK_POINTS.toFloat()
            else -> MISS_POINTS.toFloat()
        }
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

    private fun scoreDuration(actualMs: Long, expectedMs: Long): Float {
        if (expectedMs <= 0) return 100f
        val ratio = min(actualMs, expectedMs).toFloat() / max(actualMs, expectedMs).toFloat()
        return ratio * 100f
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

    private fun getEncouragingMessage(score: Float): String {
        return when {
            score >= 95f -> "🌟 Outstanding! 🌟"
            score >= 80f -> "🎉 Excellent! 🎉"
            score >= 65f -> "👏 Well done! 👏"
            score >= 50f -> "💪 Keep it up! 💪"
            score >= 30f -> "🎵 Getting there! 🎵"
            else -> "🎶 Keep practicing! 🎶"
        }
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

enum class PitchAccuracy { PERFECT, GREAT, GOOD, OK, MISS }

data class MelodyScore(
    val overallScore: Float,
    val totalNotes: Int,
    val notesHit: Int,
    val perfectNotes: Int,
    val greatNotes: Int,
    val longestStreak: Int,
    val avgPitchScore: Float,
    val avgDurationScore: Float,
    val encouragingMessage: String = ""
) {
    companion object {
        val EMPTY = MelodyScore(0f, 0, 0, 0, 0, 0, 0f, 0f, "🎶 Keep practicing! 🎶")
    }
}
