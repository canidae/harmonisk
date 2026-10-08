package net.exent.harmonisk.ui.tuner

import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult

data class TunerState(
    val displayedResult: PitchResult = PitchResult.SILENT,
    val smoothedCentsOff: Float = 0f,
    val referenceFrequency: Float = NoteUtils.DEFAULT_A4_FREQ
)
