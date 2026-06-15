package net.exent.perfectpitch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.exent.perfectpitch.ui.theme.DarkBg

// Panel / overlay style tokens shared across play screens
internal val PANEL_BG = Color(0xDD0D0D1A)
internal val PANEL_ROW2_BG = Color(0xBB0D0D1A)
internal val OVERLAY_BG = DarkBg.copy(alpha = 0.67f)
internal val OVERLAY_ICON_TINT = Color.White.copy(alpha = 0.9f)
internal val PANEL_BTN_SIZE = 32.dp
internal val OVERLAY_BUTTON_SIZE = 40.dp

// Fall speed bounds shared between MelodyViewModel and FreePlayViewModel
internal const val MIN_FALL_SPEED = 30f
internal const val MAX_FALL_SPEED = 600f

// Note divisions for the free-play beat grid (order matters: index is used by Slider)
internal data class NoteDivisionOption(val division: Int, val label: String)
internal val NOTE_DIVISION_OPTIONS = listOf(
    NoteDivisionOption(4, "♩ Quarter"),
    NoteDivisionOption(8, "♪ 8th"),
    NoteDivisionOption(16, "♬ 16th"),
    NoteDivisionOption(32, "32nd")
)
