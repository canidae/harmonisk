package net.exent.harmonisk.audio

interface NotePlayer {
    val isReady: Boolean
    fun noteOn(channel: Int, note: Int, velocity: Int)
    fun noteOff(channel: Int, note: Int)
    fun allNotesOff()
}
