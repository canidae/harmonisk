package com.perfectpitch.ui.freeplay

import com.perfectpitch.music.PitchResult

data class FreePlayState(
    val pitchResult: PitchResult = PitchResult.SILENT,
    val isListening: Boolean = false,
    val playedNotes: List<PlayedNote> = emptyList(),
    val activeNote: PlayedNote? = null,
    // Auto-expanding range based on notes played
    val detectedMinMidi: Int = 60,
    val detectedMaxMidi: Int = 72,
    // View controls (user-adjustable via gestures)
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    val fallSpeedPxPerSec: Float = 150f,
    val timeOffsetMs: Long = 0,
    // When false, time only advances while a note is being detected
    val timeProgressionEnabled: Boolean = true
)

data class PlayedNote(
    val noteName: String,
    val octave: Int,
    val midiNumber: Int,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val averageCentsOff: Float = 0f,
    val centsHistory: List<Float> = emptyList()
) {
    val isActive: Boolean get() = endTimeMs == null

    val durationMs: Long
        get() = (endTimeMs ?: System.currentTimeMillis()) - startTimeMs
}
