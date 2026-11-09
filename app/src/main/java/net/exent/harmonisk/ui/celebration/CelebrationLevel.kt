package net.exent.harmonisk.ui.celebration

import nl.dionsegijn.konfetti.core.models.Size

enum class CelebrationLevel(
    val scoreThreshold: Int,
    val pulseScale: Float,
    val confettiSizes: List<Size>,
    val musicNoteCount: Int,
    val fireworksBursts: Int,
) {
    NONE(0, 1.0f, emptyList(), 0, 0),
    BASE(1, 1.02f, listOf(Size(4), Size(8)), 0, 0),
    MODERATE(30, 1.1f, listOf(Size(8), Size(14)), 5, 0),
    STRONG(50, 1.1f, listOf(Size(10), Size(18)), 10, 0),
    INTENSE(65, 1.1f, listOf(Size(12), Size(22)), 15, 1),
    EXTREME(80, 1.15f, listOf(Size(14), Size(26)), 25, 3),
    ECSTATIC(95, 1.15f, listOf(Size(18), Size(34)), 40, 5);

    companion object {
        private const val MAX_LEVEL = 5
        private const val DRIZZLE_CONFETTI = 20
        private const val STEADY_CONFETTI = 45
        private const val HEAVY_CONFETTI = 90

        fun fromScore(score: Float): CelebrationLevel =
            entries.lastOrNull { score >= it.scoreThreshold } ?: NONE

        fun confettiCount(score: Float, level: Int): Int = when {
            score < 1f -> 0
            score < 60f -> DRIZZLE_CONFETTI
            score < 90f -> STEADY_CONFETTI
            else -> HEAVY_CONFETTI
        } * level.coerceIn(1, MAX_LEVEL)
    }
}
