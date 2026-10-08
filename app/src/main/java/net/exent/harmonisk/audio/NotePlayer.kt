package net.exent.harmonisk.audio

/** Abstracts over internal synthesizer and external MIDI output for playing notes. */
interface NotePlayer {
    val isReady: Boolean
    fun noteOn(channel: Int, note: Int, velocity: Int)
    fun noteOff(channel: Int, note: Int)
    fun allNotesOff()
    fun setInstrument(bank: Int, program: Int) {}
}
