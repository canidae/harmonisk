package net.exent.harmonisk.ui.utils

import net.exent.harmonisk.music.ScoringEngine

fun formatScore(score: Float): String = ScoringEngine.displayedScore(score).toString()
