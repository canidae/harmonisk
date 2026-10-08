package net.exent.harmonisk.music

/**
 * Result of pitch detection for a single audio frame.
 *
 * [confidence] is the detector's own certainty in [frequency], 0..1. Sources that know the
 * pitch exactly (MIDI input) report 1.
 */
data class PitchResult(
    val frequency: Float,
    val noteName: String,
    val octave: Int,
    val centsOff: Float,
    val midiNoteNumber: Int,
    val isSilent: Boolean,
    val confidence: Float = 1f
) {
    val isValid: Boolean get() = !isSilent

    companion object {
        val SILENT = PitchResult(
            frequency = 0f,
            noteName = "-",
            octave = 0,
            centsOff = 0f,
            midiNoteNumber = 0,
            isSilent = true
        )
    }
}
