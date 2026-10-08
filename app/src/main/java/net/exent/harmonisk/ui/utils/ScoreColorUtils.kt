package net.exent.harmonisk.ui.utils

import androidx.compose.ui.graphics.Color
import net.exent.harmonisk.ui.theme.*

fun scoreColor(score: Float): Color = when {
    score >= 95f -> Green80
    score >= 60f -> Teal80
    score >= 20f -> Yellow80
    else -> Red80
}
