package net.exent.harmonisk.music

data class PitchResult(
    val frequency: Float,
    val noteName: String,
    val octave: Int,
    val centsOff: Float,
    val midiNoteNumber: Int,
    val isSilent: Boolean,
    val confidence: Float = 1f,
    val sounding: Boolean = true
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
