package com.perfectpitch.audio

import com.perfectpitch.midi.MelodyTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Plays a [MelodyTrack] through a [NotePlayer] in standalone or game-synced mode. */
class MelodyPlayer(
    private val synthesizer: MelodySynthesizer
) {
    @Volatile private var notePlayer: NotePlayer = synthesizer
    private var playJob: Job? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var lastNoteOnIndex: Int = -1
    private var lastNoteOnMidi: Int = -1
    private var audioOutputDelayMs: Long = 0L

    /**
     * Switch the backend at runtime. Silences the current player before switching.
     * Used to toggle between internal synthesizer and MIDI device output.
     */
    fun setNotePlayer(player: NotePlayer) {
        notePlayer.allNotesOff()
        notePlayer = player
    }

    /** Plays a melody with absolute timing. More accurate than sequential playback. */
    fun playWithTiming(
        melodyTrack: MelodyTrack,
        scope: CoroutineScope,
        tempo: Float = 1.0f
    ) {
        stop()
        _isPlaying.value = true

        playJob = scope.launch {
            try {
                val startTimeMs = System.currentTimeMillis()
                val notes = melodyTrack.notes.sortedBy { it.startTick }

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
                    val waitMs = startTimeMs + event.timeMs - System.currentTimeMillis()
                    if (waitMs > 0) delay(waitMs)
                    if (event.isOn) notePlayer.noteOn(0, event.noteNumber, event.velocity)
                    else notePlayer.noteOff(0, event.noteNumber)
                }
            } finally {
                notePlayer.allNotesOff()
                _isPlaying.value = false
            }
        }
    }

    /**
     * Drives note-on/off from an external game clock.
     * Fires notes ahead by [audioOutputDelayMs] to compensate for speaker latency.
     */
    fun updateGameTime(gameTimeSeconds: Double, track: MelodyTrack) {
        if (!notePlayer.isReady) return

        val effectiveTime = gameTimeSeconds + audioOutputDelayMs / 1000.0
        val noteIndex = track.notes.indexOfFirst { note ->
            val start = track.noteStartSeconds(note)
            effectiveTime >= start && effectiveTime < start + track.noteDurationSeconds(note)
        }

        if (noteIndex >= 0 && noteIndex != lastNoteOnIndex) {
            if (lastNoteOnMidi >= 0) notePlayer.noteOff(0, lastNoteOnMidi)
            val note = track.notes[noteIndex]
            notePlayer.noteOn(0, note.noteNumber, note.velocity.coerceIn(1, 127))
            lastNoteOnIndex = noteIndex
            lastNoteOnMidi = note.noteNumber
        } else if (noteIndex < 0 && lastNoteOnMidi >= 0) {
            notePlayer.noteOff(0, lastNoteOnMidi)
            lastNoteOnIndex = -1
            lastNoteOnMidi = -1
        }
    }

    fun setAudioOutputDelay(delayMs: Long) {
        audioOutputDelayMs = delayMs.coerceIn(-200, 500)
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        _isPlaying.value = false
        lastNoteOnIndex = -1
        lastNoteOnMidi = -1
        if (notePlayer.isReady) notePlayer.allNotesOff()
    }

    fun resetGameSync() {
        lastNoteOnIndex = -1
        lastNoteOnMidi = -1
        if (notePlayer.isReady) notePlayer.allNotesOff()
    }
}