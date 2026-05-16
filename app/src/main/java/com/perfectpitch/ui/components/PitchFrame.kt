package com.perfectpitch.ui.components

/**
 * A single pitch detection sample recorded during playback.
 * Shared across Free Play and Melody modes.
 */
data class PitchFrame(
    val timeMs: Long,
    val midiNote: Int,
    val centsOff: Float,
    val frequency: Float = 0f
)
