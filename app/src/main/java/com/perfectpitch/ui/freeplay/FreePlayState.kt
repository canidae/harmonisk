package com.perfectpitch.ui.freeplay

import com.perfectpitch.music.PitchResult

data class FreePlayState(
    val pitchResult: PitchResult = PitchResult.SILENT,
    val isListening: Boolean = false,
    val noteHistory: List<NoteHistoryEntry> = emptyList(),
    val sessionStats: SessionStats = SessionStats()
)

data class NoteHistoryEntry(
    val noteName: String,
    val octave: Int,
    val centsOff: Float,
    val timestamp: Long
)

data class SessionStats(
    val totalNotes: Int = 0,
    val perfectNotes: Int = 0,
    val goodNotes: Int = 0,
    val averageCentsOff: Float = 0f
) {
    val accuracyPercent: Float
        get() = if (totalNotes > 0) (perfectNotes + goodNotes).toFloat() / totalNotes * 100f else 0f
}
