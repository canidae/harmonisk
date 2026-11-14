package net.exent.harmonisk.ui.utils

import androidx.compose.ui.graphics.Color
import net.exent.harmonisk.music.NoteUtils
import net.exent.harmonisk.ui.theme.Green80
import net.exent.harmonisk.ui.theme.GreenLight
import net.exent.harmonisk.ui.theme.Orange80
import net.exent.harmonisk.ui.theme.OrangeLight
import net.exent.harmonisk.ui.theme.Red80
import net.exent.harmonisk.ui.theme.RedLight
import net.exent.harmonisk.ui.theme.Yellow80
import net.exent.harmonisk.ui.theme.YellowLight
import kotlin.math.abs

fun centsColor(centsOff: Float, darkTheme: Boolean): Color {
    val abs = abs(centsOff)
    return when {
        abs <= NoteUtils.PERFECT_CENTS -> if (darkTheme) Green80 else GreenLight
        abs <= NoteUtils.GREAT_CENTS   -> if (darkTheme) Yellow80 else YellowLight
        abs <= NoteUtils.GOOD_CENTS    -> if (darkTheme) Orange80 else OrangeLight
        else                           -> if (darkTheme) Red80 else RedLight
    }
}
