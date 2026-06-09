package net.exent.perfectpitch.midi

/** Raw MIDI note events received from or sent to a MIDI device. */
sealed class MidiNoteEvent {
    data class NoteOn(val channel: Int, val noteNumber: Int, val velocity: Int) : MidiNoteEvent()
    data class NoteOff(val channel: Int, val noteNumber: Int) : MidiNoteEvent()
}
