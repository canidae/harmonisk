package net.exent.harmonisk.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.theme.Green80
import net.exent.harmonisk.ui.theme.GreenLight
import net.exent.harmonisk.ui.theme.Red80
import net.exent.harmonisk.ui.theme.RedLight
import net.exent.harmonisk.ui.theme.Yellow80
import net.exent.harmonisk.ui.theme.YellowLight
import kotlin.math.abs

fun centsColor(centsOff: Float, darkTheme: Boolean): Color {
    val green = if (darkTheme) Green80 else GreenLight
    val yellow = if (darkTheme) Yellow80 else YellowLight
    val red = if (darkTheme) Red80 else RedLight
    val ratio = (abs(centsOff) / NoteUtils.MAX_CENTS_OFF).coerceIn(0f, 1f)
    return if (ratio <= 0.5f) lerp(green, yellow, ratio * 2f)
    else lerp(yellow, red, (ratio - 0.5f) * 2f)
}
