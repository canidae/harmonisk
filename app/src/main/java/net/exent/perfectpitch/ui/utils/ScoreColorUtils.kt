package net.exent.perfectpitch.ui.utils

import androidx.compose.ui.graphics.Color
import net.exent.perfectpitch.ui.theme.*

fun scoreColor(score: Float): Color = when {
    score >= 95f -> Green80
    score >= 60f -> Teal80
    score >= 20f -> Yellow80
    else -> Red80
}

fun levelColor(level: Int): Color = when (level) {
    1 -> Green40
    2 -> Blue40
    3 -> Yellow40
    4 -> Orange40
    5 -> Red40
    else -> Purple40
}
