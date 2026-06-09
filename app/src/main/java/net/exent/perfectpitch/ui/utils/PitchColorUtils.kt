package net.exent.perfectpitch.ui.utils

import androidx.compose.ui.graphics.Color
import net.exent.perfectpitch.music.ScoringEngine
import net.exent.perfectpitch.ui.theme.Green80
import net.exent.perfectpitch.ui.theme.Orange80
import net.exent.perfectpitch.ui.theme.Red80
import net.exent.perfectpitch.ui.theme.Yellow80
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
