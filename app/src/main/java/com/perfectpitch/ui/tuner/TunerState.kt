package com.perfectpitch.ui.tuner

import com.perfectpitch.music.PitchResult

private const val IN_TUNE_CENTS = 10

data class TunerState(
    val pitchResult: PitchResult = PitchResult.SILENT,
    val displayedResult: PitchResult = PitchResult.SILENT,
    val smoothedCentsOff: Float = 0f,
    val isHeld: Boolean = false,
    val isListening: Boolean = false,
    val referenceFrequency: Float = 440f
) {
    val isInTune: Boolean
        get() = displayedResult.isValid && kotlin.math.abs(smoothedCentsOff) <= IN_TUNE_CENTS
}
