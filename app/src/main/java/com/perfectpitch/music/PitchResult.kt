package com.perfectpitch.music

import kotlin.math.abs

/**
 * Result of pitch detection for a single audio frame.
 */
data class PitchResult(
    val frequency: Float,
    val noteName: String,
    val octave: Int,
    val centsOff: Float,
    val midiNoteNumber: Int,
    val confidence: Float,
    val rmsDb: Float,
    val isSilent: Boolean
) {
    val isValid: Boolean get() = !isSilent

    val noteWithOctave: String get() = "$noteName$octave"

    val isInTune: Boolean get() = abs(centsOff) <= ScoringEngine.PERFECT_CENTS

    companion object {
        val SILENT = PitchResult(
            frequency = 0f,
            noteName = "-",
            octave = 0,
            centsOff = 0f,
            midiNoteNumber = 0,
            confidence = 0f,
            rmsDb = -100f,
            isSilent = true
        )
    }
}
