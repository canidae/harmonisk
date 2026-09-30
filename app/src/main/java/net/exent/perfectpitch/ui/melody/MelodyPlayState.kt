package net.exent.perfectpitch.ui.melody

import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.music.MelodyScore
import net.exent.perfectpitch.music.NoteScore
import net.exent.perfectpitch.music.PitchResult
import net.exent.perfectpitch.music.ViolinPosition

enum class AccompanimentMode { ALL, EXCEPT, SELECTED_ONLY, NONE }

/** Active session tracking state. */
data class MelodyGameState(
    val phase: PlayPhase = PlayPhase.NOT_STARTED,
    /** Primary melody track used for scoring (the active voice). */
    val melodyTrack: MelodyTrack? = null,
    /** One track per voice/staff; each entry is indexed by its voice number. */
    val melodyTracks: List<MelodyTrack> = emptyList(),
    /** Which voice the user has selected to sing (index into melodyTracks). */
    val activeVoiceIndex: Int = 0,
    /** Whether to play all voices, only the selected one, or none. */
    val accompanimentMode: AccompanimentMode = AccompanimentMode.ALL,
    val currentTimeSeconds: Double = 0.0,
    val noteResults: Map<Int, NoteResultState> = emptyMap(),
    val noteScores: List<NoteScore> = emptyList(),
    val currentNoteIndex: Int = -1,
    val pitchResult: PitchResult = PitchResult.SILENT,
    val waitMode: Boolean = true,
    val transposeSemitones: Int = 0,
    val melodyScore: MelodyScore? = null,
    val noteCentsHistory: Map<Int, List<Float>> = emptyMap(),
    val noteCentsPositions: Map<Int, List<Float>> = emptyMap(),
    val notePlayProgress: Map<Int, Float> = emptyMap(),
    val noteFirstDetectionStart: Map<Int, Float> = emptyMap(),
    // Non-zero during a mid-song countdown resume: the game-time value at which scoring starts
    val countdownResumeTarget: Double = 0.0,
    /** Song time of a running melody preview, or null when nothing is being previewed. */
    val previewTimeSeconds: Double? = null,
    // Metronome
    val metronomeEnabled: Boolean = false,
    val metronomeTrack: MelodyTrack? = null,
    val currentLevel: Int = 1,
    val melodyName: String? = null,
    val hideNoteBars: Boolean = false
) {
    /**
     * True when the view is already scrolled to (or before) the first note.
     * Used in FINISHED state to hide the "Scroll to start" button once there.
     */
    fun isAtScrollStart(songTimeOffsetSeconds: Double): Boolean {
        val track = melodyTrack ?: return true
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        return currentTimeSeconds + songTimeOffsetSeconds <= firstNoteStart + 0.05
    }
}

/** Rendering and scroll parameters. */
data class MelodyViewState(
    val fallSpeedPxPerSec: Float = 75f,
    val songTimeOffsetSeconds: Double = 0.0,
    val viewMinMidi: Float = 60f,
    val viewMaxMidi: Float = 72f,
    /** Recommended violin fingerings per active melody note (null = not playable in first position). */
    val violinPositions: List<ViolinPosition?> = emptyList()
)

/** MIDI device availability state. */
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

enum class NoteResultState {
    ACTIVE, PERFECT, GREAT, GOOD, OK, MISS
}

/**
 * How far the bars run ahead of the game clock.
 *
 * The bars lead the beat by the input delay, so that a note played as a bar reaches the now line is
 * scored against that note once the microphone and the detector have caught up: the sound arrives
 * that much later than the player made it.
 *
 * A song that stops for the player leads the same way. The clock comes to rest on the note one input
 * delay after its bar reached the line, so the bar runs on that much past the line before it stops,
 * and the wait is timed from where it comes to rest. By then the sound the player made as the bar
 * reached the line has been detected, so the input delay is already out of the time the song waited
 * and must not be taken off it again.
 *
 * READY is a still picture of the music, a song the player has not started, and is drawn on the now
 * line.
 */
fun nowLineLeadSeconds(phase: PlayPhase, inputDelayMs: Int): Double =
    if (phase == PlayPhase.READY) 0.0 else inputDelayMs / 1000.0

/** Level and score of the last take, as shown next to the song title. */
fun levelAndScoreText(level: Int, latestScore: String?): String =
    if (latestScore == null) "Lv.$level" else "Lv.$level  Score: $latestScore"

/**
 * The voices the app may sound while the player is being scored.
 *
 * Playing voices is a developer feature: outside developer mode the song stays silent under the
 * player's own take, so the melody has to be played by ear. Listening to the song first is a preview,
 * which is played before the take starts.
 */
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

/** The music the player has to play: from the first note of the song to the end of the last one. */
fun songPlayingSeconds(track: MelodyTrack): Double {
    val firstNote = track.notes.minByOrNull { it.startTick } ?: return 0.0
    return (track.durationSeconds - track.noteStartSeconds(firstNote)).coerceAtLeast(0.0)
}

/**
 * How long the player took, against the music they had to play.
 *
 * A song that waits for the player holds its clock for them, so that time is added to the music's
 * own length. The two bars counted in before the first note, any rest the song opens with, and the
 * slack the song lingers for after its last note are the song's own time, not the player's, and are
 * left out of both the measurement and the song length it is measured against.
 */
fun songTimeRatio(track: MelodyTrack, waitMs: Long): Double {
    val songSeconds = songPlayingSeconds(track)
    if (songSeconds <= 0.0) return 0.0
    return (songSeconds + waitMs / 1000.0) / songSeconds
}
