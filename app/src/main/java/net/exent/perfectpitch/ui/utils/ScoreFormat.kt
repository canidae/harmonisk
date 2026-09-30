package net.exent.perfectpitch.ui.utils

import kotlin.math.roundToInt

/**
 * A score as it is shown to the player: a whole number, rounded the same way wherever it appears.
 * The result modal and the song list must not disagree about what a score of 87.5 is.
 */
fun formatScore(score: Float): String = score.roundToInt().toString()
