package net.exent.perfectpitch.ui.tuner

import net.exent.perfectpitch.music.PitchResult

data class TunerState(
    val displayedResult: PitchResult = PitchResult.SILENT,
    val smoothedCentsOff: Float = 0f,
    val referenceFrequency: Float = 440f
)
