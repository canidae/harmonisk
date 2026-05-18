package com.perfectpitch.ui.theme

import androidx.compose.ui.graphics.Color
import com.perfectpitch.music.ScoringEngine
import kotlin.math.abs

// In-tune / success colors
val Green80 = Color(0xFFA5D6A7)
val Green40 = Color(0xFF388E3C)

// Warning / slightly off
val Yellow80 = Color(0xFFFFF59D)
val Yellow40 = Color(0xFFF9A825)

// Caution / marginally off
val Orange80 = Color(0xFFFFCC80)
val Orange40 = Color(0xFFFF9800)

// Error / way off
val Red80 = Color(0xFFEF9A9A)
val Red40 = Color(0xFFD32F2F)

// Primary accent
val Purple80 = Color(0xFFCFBCFF)
val Purple40 = Color(0xFF7C4DFF)

// Secondary
val Teal80 = Color(0xFF80CBC4)
val Teal40 = Color(0xFF00897B)

// Backgrounds
val DarkBackground = Color(0xFF121218)
val DarkSurface = Color(0xFF1E1E2A)
val LightBackground = Color(0xFFFFFBFE)
val LightSurface = Color(0xFFF5F5F5)

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
