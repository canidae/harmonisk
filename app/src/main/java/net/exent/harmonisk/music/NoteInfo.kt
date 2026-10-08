package net.exent.harmonisk.music

data class NoteInfo(
    val name: String,
    val octave: Int,
    val centsOff: Float,
    val midiNumber: Int
)
