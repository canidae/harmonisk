package net.exent.harmonisk.music

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class ScoringEngine {

    companion object {
        const val DURATION_GRACE_LEVEL_1 = 0.85f
        const val DURATION_GRACE_LEVEL_2 = 0.875f
        const val DURATION_GRACE_LEVEL_3 = 0.90f
        const val DURATION_GRACE_LEVEL_4 = 0.925f
        const val DURATION_GRACE_LEVEL_5 = 0.95f

        fun durationGraceRatio(level: Int): Float = when (level) {
            1 -> DURATION_GRACE_LEVEL_1
            2 -> DURATION_GRACE_LEVEL_2
            3 -> DURATION_GRACE_LEVEL_3
            4 -> DURATION_GRACE_LEVEL_4
            else -> DURATION_GRACE_LEVEL_5
        }

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

        fun scorePitch(centsOff: Float, level: Int): Float {
            val absCents = abs(centsOff)
            val maxCents = NoteUtils.maxCentsOff(level)
            return when {
                absCents <= NoteUtils.FULL_PITCH_SCORE_CENTS -> 100f
                absCents >= maxCents -> 0f
                else -> (maxCents - absCents) /
                    (maxCents - NoteUtils.FULL_PITCH_SCORE_CENTS) * 100f
            }
        }

    }

    fun scoreNote(result: NotePerformance, level: Int = 5): NoteScore {
        val pitchScore = scorePitch(result.centsOff, level)
        val durationScore = scoreDuration(result.actualDurationMs, result.expectedDurationMs, level)
        val overallScore = pitchScore * PITCH_WEIGHT + durationScore * DURATION_WEIGHT

        return NoteScore(
            pitchScore = pitchScore,
            durationScore = durationScore,
            overallScore = overallScore,
            isHit = NoteUtils.isPitchAccepted(result.centsOff, level)
        )
    }

    fun scoreDuration(actualMs: Long, expectedMs: Long, level: Int = 5): Float {
        if (expectedMs <= 0) return 100f
        val ratio = min(actualMs, expectedMs).toFloat() / max(actualMs, expectedMs).toFloat()
        return min(1f, ratio / durationGraceRatio(level)) * 100f
    }

    fun calculateMelodyScore(noteScores: List<NoteScore>): MelodyScore {
        if (noteScores.isEmpty()) return MelodyScore.EMPTY

        val avgScore = noteScores.map { it.overallScore }.average().toFloat()
        val notesHit = noteScores.count { it.isHit }

        return MelodyScore(
            overallScore = avgScore,
            totalNotes = noteScores.size,
            notesHit = notesHit,
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
    val isHit: Boolean
)


data class MelodyScore(
    val overallScore: Float,
    val totalNotes: Int,
    val notesHit: Int,
    val avgPitchScore: Float,
    val avgDurationScore: Float,
    val timeScore: Float = 1.0f,
    val wrongNoteScore: Float = 100f
) {
    companion object {
        val EMPTY = MelodyScore(0f, 0, 0, 0f, 0f)
    }
}
