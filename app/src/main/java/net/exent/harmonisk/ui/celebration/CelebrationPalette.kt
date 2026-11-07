package net.exent.harmonisk.ui.celebration

import androidx.compose.ui.graphics.Color

internal val CELEBRATION_COLOR_INTS = listOf(
    0xFFCFBCFF.toInt(),
    0xFFA5D6A7.toInt(),
    0xFF80CBC4.toInt(),
    0xFFFFF59D.toInt(),
    0xFFFFCC80.toInt(),
    0xFFFF80AB.toInt(),
    0xFF80D8FF.toInt(),
    0xFFCCFF90.toInt()
)

internal val CELEBRATION_COLORS = CELEBRATION_COLOR_INTS.map { Color(it) }
