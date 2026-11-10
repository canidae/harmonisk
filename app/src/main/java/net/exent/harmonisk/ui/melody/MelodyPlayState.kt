package net.exent.harmonisk.ui.melody

import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.music.MelodyScore
import net.exent.harmonisk.music.NoteScore
import net.exent.harmonisk.music.PitchResult
import net.exent.harmonisk.music.BowedPosition

enum class AccompanimentMode { ALL, EXCEPT, SELECTED_ONLY, NONE }

data class MelodyGameState(
    val phase: PlayPhase = PlayPhase.NOT_STARTED,
    val melodyTrack: MelodyTrack? = null,
    val melodyTracks: List<MelodyTrack> = emptyList(),
    val activeVoiceIndex: Int = 0,
    val accompanimentMode: AccompanimentMode = AccompanimentMode.ALL,
    val currentTimeSeconds: Double = 0.0,
    val noteResults: Map<Int, NoteResultState> = emptyMap(),
    val noteScores: List<NoteScore> = emptyList(),
    val currentNoteIndex: Int = -1,
    val pitchResult: PitchResult = PitchResult.SILENT,
    val waitMode: Boolean = true,
    val autoTranspose: Boolean = true,
    val transposeSemitones: Int = 0,
    val preferOpenStrings: Boolean = true,
    val melodyScore: MelodyScore? = null,
    val noteCentsHistory: Map<Int, List<Float>> = emptyMap(),
    val noteCentsPositions: Map<Int, List<Float>> = emptyMap(),
    val notePlayProgress: Map<Int, Float> = emptyMap(),
    val noteFirstDetectionStart: Map<Int, Float> = emptyMap(),
    val countdownResumeTarget: Double = 0.0,
    val previewTimeSeconds: Double? = null,
    val cue: PlayCue? = null,
    val metronomeEnabled: Boolean = true,
    val metronomeTrack: MelodyTrack? = null,
    val currentLevel: Int = 1,
    val unlockedLevel: Int = 1,
    val playedLevel: Int = 1,
    val newlyUnlockedLevel: Int? = null,
    val melodyName: String? = null,
    val hideNoteBars: Boolean = false,
    val settingsLoaded: Boolean = false
) {
    fun isAtScrollStart(songTimeOffsetSeconds: Double): Boolean {
        val track = melodyTrack ?: return true
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        return currentTimeSeconds + songTimeOffsetSeconds <= firstNoteStart + 0.05
    }
}

data class MelodyViewState(
    val fallSpeedPxPerSec: Float = 75f,
    val songTimeOffsetSeconds: Double = 0.0,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    val bowedPositions: List<BowedPosition?> = emptyList()
)

data class MelodyDeviceState(
    val midiAvailable: Boolean = false,
    val midiInputEnabled: Boolean = false,
    val midiOutputEnabled: Boolean = false
)

enum class PlayPhase {
    NOT_STARTED,
    READY,
    PLAYING,
    PAUSED,
    WAITING_FOR_NOTE,
    FINISHED
}

enum class PlayCue {
    PREVIEW,
    YOUR_TURN
}

enum class NoteResultState {
    ACTIVE, PERFECT, GREAT, GOOD, OK, MISS
}

fun nowLineLeadSeconds(phase: PlayPhase, inputDelayMs: Int): Double =
    if (phase == PlayPhase.READY) 0.0 else inputDelayMs / 1000.0

fun audioLeadSeconds(inputDelayMs: Int, outputDelayMs: Int): Double =
    (inputDelayMs + outputDelayMs) / 1000.0

fun previewClockSeconds(
    startSeconds: Double,
    leadInSeconds: Double,
    elapsedSeconds: Double,
    outputDelayMs: Int
): Double = startSeconds + elapsedSeconds - leadInSeconds - outputDelayMs / 1000.0

fun audibleMelodyTracks(
    melodyTracks: List<MelodyTrack>,
    activeVoiceIndex: Int,
    accompanimentMode: AccompanimentMode,
    devModeEnabled: Boolean
): List<MelodyTrack> {
    if (!devModeEnabled) return emptyList()
    return when (accompanimentMode) {
        AccompanimentMode.ALL -> melodyTracks
        AccompanimentMode.EXCEPT -> melodyTracks.filterIndexed { i, _ -> i != activeVoiceIndex }
        AccompanimentMode.SELECTED_ONLY ->
            melodyTracks.getOrNull(activeVoiceIndex)?.let { listOf(it) } ?: emptyList()
        AccompanimentMode.NONE -> emptyList()
    }
}

fun songPlayingSeconds(track: MelodyTrack): Double {
    val firstNote = track.notes.minByOrNull { it.startTick } ?: return 0.0
    return (track.durationSeconds - track.noteStartSeconds(firstNote)).coerceAtLeast(0.0)
}

fun previewStartSeconds(track: MelodyTrack, timeSeconds: Double): Double {
    if (timeSeconds <= 0.0) return 0.0
    val tick = track.secondsToTick(timeSeconds)
    val timeSig = track.timeSignatureAt(tick)
    val ticksPerBar = track.ticksPerQuarterNote.toLong() * 4 * timeSig.numerator / timeSig.denominator
    if (ticksPerBar <= 0) return 0.0
    val previousBarTick = (tick / ticksPerBar - 1) * ticksPerBar
    return if (previousBarTick <= 0) 0.0 else track.tickToSeconds(previousBarTick)
}

fun songTimeRatio(track: MelodyTrack, waitMs: Long): Double {
    val songSeconds = songPlayingSeconds(track)
    if (songSeconds <= 0.0) return 0.0
    return (songSeconds + waitMs / 1000.0) / songSeconds
}
