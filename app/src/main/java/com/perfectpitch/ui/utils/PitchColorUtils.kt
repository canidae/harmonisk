package com.perfectpitch.ui.utils

import androidx.compose.ui.graphics.Color
import com.perfectpitch.music.ScoringEngine
import com.perfectpitch.ui.theme.Green80
import com.perfectpitch.ui.theme.Orange80
import com.perfectpitch.ui.theme.Red80
import com.perfectpitch.ui.theme.Yellow80
import kotlin.math.abs

/** Maps a cents-off value to a tuning accuracy color using the scoring engine thresholds. */
fun centsColor(centsOff: Float): Color {
    val abs = abs(centsOff)
    return when {
        abs <= ScoringEngine.PERFECT_CENTS -> Green80
        abs <= ScoringEngine.GREAT_CENTS   -> Yellow80
        abs <= ScoringEngine.GOOD_CENTS    -> Orange80
        else                               -> Red80
    }
}
