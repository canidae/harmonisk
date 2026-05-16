package com.perfectpitch.audio

import com.perfectpitch.midi.MelodyTrack
import com.perfectpitch.midi.NoteEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays a [MelodyTrack] through a [MelodySynthesizer], scheduling note-on/off
 * events in real time. Supports standalone preview playback and game-synced mode.
 *
 * **Standalone mode** ([play]): Drives its own timer and plays the entire melody.
 * **Game-synced mode** ([updateGameTime]): Follows an external time source
 * (e.g., MelodyViewModel's currentTimeSeconds) and fires notes accordingly.
 */
class MelodyPlayer(
    private val synthesizer: MelodySynthesizer
) {
    private var playJob: Job? = null
    private var track: MelodyTrack? = null
    private var tempoMultiplier: Float = 1.0f
    private var audioOutputDelayMs: Long = 0L

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Tracks which note index is currently sounding to avoid duplicate note-on
    private var lastNoteOnIndex: Int = -1
    private var lastNoteOnMidi: Int = -1

    /**
     * Play a melody from start to finish (standalone preview mode).
     *
     * @param melodyTrack The melody to play.
     * @param scope Coroutine scope to launch playback in.
     * @param tempo Tempo multiplier (1.0 = original speed).
     * @param outputDelayMs Audio output delay for Bluetooth compensation (not used in standalone).
     */
    fun play(
        melodyTrack: MelodyTrack,
        scope: CoroutineScope,
        tempo: Float = 1.0f,
        outputDelayMs: Long = 0L
    ) {
        stop()
        track = melodyTrack
        tempoMultiplier = tempo
        _isPlaying.value = true

        playJob = scope.launch {
            try {
                val notes = melodyTrack.notes
                for (note in notes) {
                    if (!isActive) break
                    val startSec = melodyTrack.noteStartSeconds(note) / tempo
                    val durationSec = melodyTrack.noteDurationSeconds(note) / tempo

                    // Wait until note start time (relative to playback start)
                    val elapsedSec = currentCoroutineContext()[Job]?.let { 0.0 } ?: 0.0
                    // Simple sequential playback: wait for start, play, wait for duration
                    synthesizer.noteOn(0, note.noteNumber, note.velocity.coerceIn(1, 127))
                    delay((durationSec * 1000).toLong())
                    synthesizer.noteOff(0, note.noteNumber)

                    // Wait for gap to next note
                    val nextNote = notes.getOrNull(notes.indexOf(note) + 1)
                    if (nextNote != null) {
                        val nextStartSec = melodyTrack.noteStartSeconds(nextNote) / tempo
                        val gapSec = nextStartSec - (startSec + durationSec)
                        if (gapSec > 0) {
                            delay((gapSec * 1000).toLong())
                        }
                    }
                }
            } finally {
                synthesizer.allNotesOff()
                _isPlaying.value = false
            }
        }
    }

    /**
     * Play a melody with proper absolute timing (standalone preview mode).
     * More accurate than sequential playback for melodies with overlapping notes
     * or complex timing.
     */
    fun playWithTiming(
        melodyTrack: MelodyTrack,
        scope: CoroutineScope,
        tempo: Float = 1.0f
    ) {
        stop()
        track = melodyTrack
        tempoMultiplier = tempo
        _isPlaying.value = true

        playJob = scope.launch {
            try {
                val startTimeMs = System.currentTimeMillis()
                val notes = melodyTrack.notes.sortedBy { it.startTick }

                // Schedule all note-on and note-off events
                data class TimedEvent(val timeMs: Long, val noteNumber: Int, val isOn: Boolean, val velocity: Int)

                val events = mutableListOf<TimedEvent>()
                for (note in notes) {
                    val startMs = (melodyTrack.noteStartSeconds(note) / tempo * 1000).toLong()
                    val endMs = ((melodyTrack.noteStartSeconds(note) + melodyTrack.noteDurationSeconds(note)) / tempo * 1000).toLong()
                    events.add(TimedEvent(startMs, note.noteNumber, true, note.velocity.coerceIn(1, 127)))
                    events.add(TimedEvent(endMs, note.noteNumber, false, 0))
                }
                events.sortBy { it.timeMs }

                for (event in events) {
                    if (!isActive) break
                    val targetTimeMs = startTimeMs + event.timeMs
                    val now = System.currentTimeMillis()
                    val waitMs = targetTimeMs - now
                    if (waitMs > 0) delay(waitMs)

                    if (event.isOn) {
                        synthesizer.noteOn(0, event.noteNumber, event.velocity)
                    } else {
                        synthesizer.noteOff(0, event.noteNumber)
                    }
                }
            } finally {
                synthesizer.allNotesOff()
                _isPlaying.value = false
            }
        }
    }

    /**
     * Update the player with the current game time. Used during gameplay
     * to sync melody audio with the falling bars.
     *
     * Fires note-on/off messages as the game time crosses note boundaries.
     * The [audioOutputDelayMs] causes notes to fire early so audio arrives
     * at the user's ears in sync with the visual bar position.
     *
     * @param gameTimeSeconds The current game time in seconds.
     * @param track The melody track being played.
     */
    fun updateGameTime(gameTimeSeconds: Double, track: MelodyTrack) {
        if (!synthesizer.isReady) return

        // Look ahead by audioOutputDelayMs to compensate for output latency
        val lookAheadSeconds = audioOutputDelayMs / 1000.0
        val effectiveTime = gameTimeSeconds + lookAheadSeconds

        // Find the note that should be sounding at effectiveTime
        val noteIndex = track.notes.indexOfFirst { note ->
            val start = track.noteStartSeconds(note)
            val end = start + track.noteDurationSeconds(note)
            effectiveTime >= start && effectiveTime < end
        }

        if (noteIndex >= 0 && noteIndex != lastNoteOnIndex) {
            // Turn off previous note
            if (lastNoteOnMidi >= 0) {
                synthesizer.noteOff(0, lastNoteOnMidi)
            }
            // Turn on new note
            val note = track.notes[noteIndex]
            synthesizer.noteOn(0, note.noteNumber, note.velocity.coerceIn(1, 127))
            lastNoteOnIndex = noteIndex
            lastNoteOnMidi = note.noteNumber
        } else if (noteIndex < 0 && lastNoteOnMidi >= 0) {
            // We're in a gap between notes — silence
            synthesizer.noteOff(0, lastNoteOnMidi)
            lastNoteOnIndex = -1
            lastNoteOnMidi = -1
        }
    }

    /** Set the audio output delay for Bluetooth compensation. */
    fun setAudioOutputDelay(delayMs: Long) {
        audioOutputDelayMs = delayMs.coerceIn(-200, 500)
    }

    /** Stop any ongoing playback and silence all notes. */
    fun stop() {
        playJob?.cancel()
        playJob = null
        _isPlaying.value = false
        lastNoteOnIndex = -1
        lastNoteOnMidi = -1
        if (synthesizer.isReady) {
            synthesizer.allNotesOff()
        }
    }

    /** Reset game-sync state (call when starting a new melody or restarting). */
    fun resetGameSync() {
        lastNoteOnIndex = -1
        lastNoteOnMidi = -1
        if (synthesizer.isReady) {
            synthesizer.allNotesOff()
        }
    }
}
