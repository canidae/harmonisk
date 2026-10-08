package net.exent.harmonisk.ui.celebration

sealed class CelebrationPayload {
    data class Pulsing(val scale: Float) : CelebrationPayload()
    data class Confetti(val count: Int) : CelebrationPayload()
    data class MusicNotes(val count: Int) : CelebrationPayload()
    data class Fireworks(val burstCount: Int) : CelebrationPayload()
    data class Jitter(val amplitude: Float, val frequency: Float) : CelebrationPayload()
    data class Haptic(val intensity: Int) : CelebrationPayload()
}

fun CelebrationLevel.toPayloads(): List<CelebrationPayload> = when (this) {
    CelebrationLevel.NONE -> emptyList()
    CelebrationLevel.BASE -> listOf(CelebrationPayload.Pulsing(pulseScale))
    CelebrationLevel.MODERATE -> listOf(
        CelebrationPayload.Pulsing(pulseScale),
        CelebrationPayload.Confetti(confettiCount),
        CelebrationPayload.MusicNotes(musicNoteCount)
    )

    CelebrationLevel.STRONG -> listOf(
        CelebrationPayload.Pulsing(pulseScale),
        CelebrationPayload.Confetti(confettiCount),
        CelebrationPayload.MusicNotes(musicNoteCount)
    )

    CelebrationLevel.INTENSE -> listOf(
        CelebrationPayload.Pulsing(pulseScale),
        CelebrationPayload.Confetti(confettiCount),
        CelebrationPayload.MusicNotes(musicNoteCount),
        CelebrationPayload.Fireworks(fireworksBursts),
        CelebrationPayload.Haptic(1)
    )

    CelebrationLevel.EXTREME -> listOf(
        CelebrationPayload.Pulsing(pulseScale),
        CelebrationPayload.Confetti(confettiCount),
        CelebrationPayload.MusicNotes(musicNoteCount),
        CelebrationPayload.Fireworks(fireworksBursts),
        CelebrationPayload.Haptic(2)
    )

    CelebrationLevel.ECSTATIC -> listOf(
        CelebrationPayload.Pulsing(pulseScale),
        CelebrationPayload.Confetti(confettiCount),
        CelebrationPayload.MusicNotes(musicNoteCount),
        CelebrationPayload.Fireworks(fireworksBursts),
        CelebrationPayload.Jitter(2f, 20f),
        CelebrationPayload.Haptic(3)
    )
}
