package net.exent.harmonisk.ui.utils

import androidx.compose.ui.graphics.Color
import net.exent.harmonisk.ui.theme.*

fun scoreColor(score: Float, darkTheme: Boolean): Color = when {
    score >= 95f -> if (darkTheme) Green80 else GreenLight
    score >= 60f -> if (darkTheme) Teal80 else TealLight
    score >= 20f -> if (darkTheme) Yellow80 else YellowLight
    else -> if (darkTheme) Red80 else RedLight
}
