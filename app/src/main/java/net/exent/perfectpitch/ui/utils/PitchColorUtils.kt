package net.exent.perfectpitch.ui.utils

import androidx.compose.ui.graphics.Color
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.ui.theme.Green80
import net.exent.perfectpitch.ui.theme.Orange80
import net.exent.perfectpitch.ui.theme.Red80
import net.exent.perfectpitch.ui.theme.Yellow80
import kotlin.math.abs

fun centsColor(centsOff: Float): Color {
    val abs = abs(centsOff)
    return when {
        abs <= NoteUtils.PERFECT_CENTS -> Green80
        abs <= NoteUtils.GREAT_CENTS   -> Yellow80
        abs <= NoteUtils.GOOD_CENTS    -> Orange80
        else                           -> Red80
    }
}
