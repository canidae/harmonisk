package net.exent.perfectpitch.ui.tuner

import net.exent.perfectpitch.music.PitchResult

data class TunerState(
    val displayedResult: PitchResult = PitchResult.SILENT,
    val smoothedCentsOff: Float = 0f,
    val isHeld: Boolean = false,
    val isListening: Boolean = false,
    val referenceFrequency: Float = 440f,
    val isInTune: Boolean = false
)
