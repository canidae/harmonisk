package com.perfectpitch.audio

/** Abstracts over internal synthesizer and external MIDI output for playing notes. */
interface NotePlayer {
    val isReady: Boolean
    fun noteOn(channel: Int, note: Int, velocity: Int)
    fun noteOff(channel: Int, note: Int)
    fun allNotesOff()
}
