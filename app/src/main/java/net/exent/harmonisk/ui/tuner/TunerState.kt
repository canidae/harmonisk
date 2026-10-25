package net.exent.harmonisk.ui.tuner

import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.music.PitchResult

data class TunerState(
    val displayedResult: PitchResult = PitchResult.SILENT,
    val smoothedCentsOff: Float = 0f,
    val concertPitchA4: Float = NoteUtils.DEFAULT_CONCERT_PITCH,
    val isInTune: Boolean = false
)
