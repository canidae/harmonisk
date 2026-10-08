package net.exent.harmonisk.ui.celebration

import nl.dionsegijn.konfetti.core.models.Size

enum class CelebrationLevel(
    val scoreThreshold: Int,
    val pulseScale: Float,
    val confettiCount: Int,
    val confettiSizes: List<Size>,
    val musicNoteCount: Int,
    val fireworksBursts: Int,
) {
    NONE(0, 1.0f, 0, emptyList(), 0, 0),
    BASE(50, 1.05f, 0, listOf(Size(6), Size(10)), 0, 0),
    MODERATE(100, 1.1f, 50, listOf(Size(8), Size(14)), 5, 0),
    STRONG(200, 1.1f, 150, listOf(Size(10), Size(18)), 10, 0),
    INTENSE(300, 1.1f, 200, listOf(Size(12), Size(22)), 15, 1),
    EXTREME(400, 1.15f, 400, listOf(Size(14), Size(26)), 25, 3),
    ECSTATIC(500, 1.15f, 1000, listOf(Size(18), Size(34)), 40, 5);

    companion object {
        fun fromScore(score: Float): CelebrationLevel =
            entries.lastOrNull { score >= it.scoreThreshold } ?: NONE
    }
}
