package net.exent.harmonisk.ui.record

enum class PianoVisibility { NONE, UPPER_ONLY, LOWER_ONLY, BOTH }

enum class OutputDevice { SYNTH, NONE, MIDI }

data class RecordState(
    val isRecording: Boolean = false,
    val playedNotes: List<PlayedNote> = emptyList(),
    val activeNote: PlayedNote? = null,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    val fallSpeedPxPerSec: Float = 75f,
    val songPositionMs: Long = 0L,
    val beatsPerBar: Int = 4,
    val bpm: Float = 120f,
    val minNoteDivision: Int = 16,
    val snapEnabled: Boolean = true,
    val activeVoiceIndex: Int = 0,
    val voiceCount: Int = 1,
    val loopStartBar: Int? = null,
    val loopEndBar: Int? = null,
    val selectedNoteId: String? = null,
    val midiInputEnabled: Boolean = false,
    val outputDevice: OutputDevice = OutputDevice.SYNTH,
    val midiInputDeviceName: String? = null,
    val midiOutputDeviceName: String? = null,
    val isPlaying: Boolean = false,
    val pianoVisibility: PianoVisibility = PianoVisibility.UPPER_ONLY,
    val barDivision: Int = 4,
    val metronomeEnabled: Boolean = true
)

data class PlayedNote(
    val noteName: String,
    val octave: Int,
    val midiNumber: Int,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val centsHistory: List<Float> = emptyList(),
    val id: String,
    val voiceIndex: Int = 0,
    val trackOrigin: Int = 0
) {
    val isActive: Boolean get() = endTimeMs == null
}
