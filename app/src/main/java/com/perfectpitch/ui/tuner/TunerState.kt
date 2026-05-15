package com.perfectpitch.ui.tuner

import com.perfectpitch.music.PitchResult

data class TunerState(
    val pitchResult: PitchResult = PitchResult.SILENT,
    val isListening: Boolean = false,
    val referenceFrequency: Float = 440f,
    val inTuneCents: Int = 10
) {
    val isInTune: Boolean
        get() = pitchResult.isValid && kotlin.math.abs(pitchResult.centsOff) <= inTuneCents
}
