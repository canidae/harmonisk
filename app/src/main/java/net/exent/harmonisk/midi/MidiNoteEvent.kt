package net.exent.harmonisk.midi

sealed class MidiNoteEvent {
    data class NoteOn(val noteNumber: Int) : MidiNoteEvent()
    data class NoteOff(val noteNumber: Int) : MidiNoteEvent()
}
