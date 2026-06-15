package net.exent.perfectpitch.ui.celebration

enum class CelebrationLevel(
    val scoreThreshold: Int,
    val pulseScale: Float,
    val confettiCount: Int,
    val fireworksBursts: Int,
    val useHaptics: Boolean,
    val jitterEnabled: Boolean
) {
    NONE(0, 1.0f, 0, 0, false, false),
    BASE(50, 1.05f, 0, 0, false, false),
    MODERATE(100, 1.1f, 50, 0, false, false),
    STRONG(200, 1.1f, 150, 0, false, false),
    INTENSE(300, 1.1f, 200, 1, true, false),
    EXTREME(400, 1.15f, 400, 3, true, false),
    ECSTATIC(500, 1.15f, 1000, 5, true, true);

    companion object {
        fun fromScore(score: Float): CelebrationLevel =
            entries.lastOrNull { score >= it.scoreThreshold } ?: NONE
    }
}
