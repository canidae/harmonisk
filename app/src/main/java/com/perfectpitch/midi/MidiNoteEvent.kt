package com.perfectpitch.midi

/** Raw MIDI note events received from or sent to a MIDI device. */
sealed class MidiNoteEvent {
    data class NoteOn(val channel: Int, val midiNote: Int, val velocity: Int) : MidiNoteEvent()
    data class NoteOff(val channel: Int, val midiNote: Int) : MidiNoteEvent()
}
