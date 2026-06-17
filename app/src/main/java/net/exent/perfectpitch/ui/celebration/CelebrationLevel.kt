package net.exent.perfectpitch.ui.celebration

import nl.dionsegijn.konfetti.core.models.Size

enum class CelebrationLevel(
    val scoreThreshold: Int,
    val pulseScale: Float,
    val confettiCount: Int,
    val confettiSizes: List<Size>,
    val musicNoteCount: Int,
    val fireworksBursts: Int,
    val useHaptics: Boolean,
    val jitterEnabled: Boolean
) {
    NONE(0, 1.0f, 0, emptyList(), 0, 0, false, false),
    BASE(50, 1.05f, 0, listOf(Size(6), Size(10)), 0, 0, false, false),
    MODERATE(100, 1.1f, 50, listOf(Size(8), Size(14)), 5, 0, false, false),
    STRONG(200, 1.1f, 150, listOf(Size(10), Size(18)), 10, 0, false, false),
    INTENSE(300, 1.1f, 200, listOf(Size(12), Size(22)), 15, 1, true, false),
    EXTREME(400, 1.15f, 400, listOf(Size(14), Size(26)), 25, 3, true, false),
    ECSTATIC(500, 1.15f, 1000, listOf(Size(18), Size(34)), 40, 5, true, true);

    companion object {
        fun fromScore(score: Float): CelebrationLevel =
            entries.lastOrNull { score >= it.scoreThreshold } ?: NONE
    }
}
