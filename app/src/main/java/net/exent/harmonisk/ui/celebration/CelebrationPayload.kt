package net.exent.harmonisk.ui.celebration

sealed class CelebrationPayload {
    data class Pulsing(val scale: Float) : CelebrationPayload()
    data class Confetti(val count: Int) : CelebrationPayload()
    data class MusicNotes(val count: Int) : CelebrationPayload()
    data class Fireworks(val burstCount: Int) : CelebrationPayload()
    data class Jitter(val amplitude: Float, val frequency: Float) : CelebrationPayload()
    data class Haptic(val intensity: Int) : CelebrationPayload()
}

fun CelebrationLevel.toPayloads(score: Float, level: Int): List<CelebrationPayload> {
    if (this == CelebrationLevel.NONE) return emptyList()
    val haptic = when (this) {
        CelebrationLevel.INTENSE -> 1
        CelebrationLevel.EXTREME -> 2
        CelebrationLevel.ECSTATIC -> 3
        else -> 0
    }
    return buildList {
        add(CelebrationPayload.Pulsing(pulseScale))
        add(CelebrationPayload.Confetti(CelebrationLevel.confettiCount(score, level)))
        if (musicNoteCount > 0) add(CelebrationPayload.MusicNotes(musicNoteCount))
        if (fireworksBursts > 0) add(CelebrationPayload.Fireworks(fireworksBursts))
        if (this@toPayloads == CelebrationLevel.ECSTATIC) add(CelebrationPayload.Jitter(2f, 20f))
        if (haptic > 0) add(CelebrationPayload.Haptic(haptic))
    }
}
