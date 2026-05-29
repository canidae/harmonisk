package com.perfectpitch.ui.freeplay

enum class NoteEditMode { NONE, EDIT }

enum class PianoVisibility { NONE, UPPER_ONLY, LOWER_ONLY, BOTH }

/** Audio output routing for playback. */
enum class OutputDevice { SYNTH, NONE, MIDI }

data class FreePlayState(
    val isRecording: Boolean = false,
    val playedNotes: List<PlayedNote> = emptyList(),
    val activeNote: PlayedNote? = null,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    val fallSpeedPxPerSec: Float = 150f,
    val songPositionMs: Long = 0L,
    val beatsPerBar: Int = 4,
    val bpm: Float = 120f,
    val minNoteDivision: Int = 16,
    val snapEnabled: Boolean = true,
    val activeVoiceIndex: Int = 0,
    val voiceCount: Int = 1,
    val loopStartBar: Int? = null,
    val loopEndBar: Int? = null,
    val isLooping: Boolean = false,
    val editMode: NoteEditMode = NoteEditMode.NONE,
    val selectedNoteId: String? = null,
    // MIDI state
    val midiAvailable: Boolean = false,
    val midiInputEnabled: Boolean = false,
    val outputDevice: OutputDevice = OutputDevice.SYNTH,
    val midiInputDeviceName: String? = null,
    val midiOutputDeviceName: String? = null,
    val isPlaying: Boolean = false,
    // Piano visibility
    val pianoVisibility: PianoVisibility = PianoVisibility.UPPER_ONLY,
    // Meter denominator (time signature note value: 2, 4, 8, 16, 32)
    val barDivision: Int = 4
)

data class PlayedNote(
    val noteName: String,
    val octave: Int,
    val midiNumber: Int,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val averageCentsOff: Float = 0f,
    val centsHistory: List<Float> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val voiceIndex: Int = 0
) {
    val isActive: Boolean get() = endTimeMs == null
}
