package net.exent.perfectpitch.midi

/** Raw MIDI note events received from or sent to a MIDI device. */
sealed class MidiNoteEvent {
    data class NoteOn(val noteNumber: Int) : MidiNoteEvent()
    data class NoteOff(val noteNumber: Int) : MidiNoteEvent()
}
