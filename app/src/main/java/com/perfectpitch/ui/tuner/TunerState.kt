package com.perfectpitch.ui.tuner

import com.perfectpitch.music.PitchResult

data class TunerState(
    val pitchResult: PitchResult = PitchResult.SILENT,
    val displayedResult: PitchResult = PitchResult.SILENT,
    val smoothedCentsOff: Float = 0f,
    val isHeld: Boolean = false,
    val isListening: Boolean = false,
    val referenceFrequency: Float = 440f,
    val inTuneCents: Int = 10
) {
    val isInTune: Boolean
        get() = displayedResult.isValid && kotlin.math.abs(smoothedCentsOff) <= inTuneCents
}
