package net.exent.perfectpitch.audio

import net.exent.perfectpitch.midi.MelodyTrack
import net.exent.perfectpitch.midi.NoteEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Plays a list of [MelodyTrack] through a [NotePlayer] in standalone or game-synced mode. */
class MelodyPlayer(
    synthesizer: NotePlayer
) {
    @Volatile private var notePlayer: NotePlayer = synthesizer
    private var playJob: Job? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val activeSynthNotes = mutableMapOf<Int, NoteEvent>()
    private var totalAudioDelayMs: Long = 0L

    /**
     * Switch the backend at runtime. Silences the current player before switching.
     * Used to toggle between internal synthesizer and MIDI device output.
     */
    fun setNotePlayer(player: NotePlayer) {
        notePlayer.allNotesOff()
        notePlayer = player
    }

    /** Plays melody tracks with absolute timing. Supports multi-track polyphonic playback. */
    fun playWithTiming(
        melodyTracks: List<MelodyTrack>,
        scope: CoroutineScope,
        tempo: Float = 1.0f
    ) {
        stop()
        _isPlaying.value = true

        playJob = scope.launch {
            try {
                val startTimeMs = System.currentTimeMillis()

                val events = mutableListOf<TimedEvent>()
                for (track in melodyTracks) {
                    val channel = track.channel
                    for (note in track.notes) {
                        val startMs = (track.noteStartSeconds(note) / tempo * 1000).toLong()
                        val endMs = (track.noteStartSeconds(note) + track.noteDurationSeconds(note))
                            .let { (it / tempo * 1000).toLong() }
                        events.add(TimedEvent(startMs, note.noteNumber, true, note.velocity.coerceIn(1, 127), channel))
                        events.add(TimedEvent(endMs, note.noteNumber, false, 0, channel))
                    }
                }
                events.sortBy { it.timeMs }

                for (event in events) {
                    if (!isActive) break
                    val waitMs = startTimeMs + event.timeMs - System.currentTimeMillis()
                    if (waitMs > 0) delay(waitMs)
                    if (event.isOn) notePlayer.noteOn(event.channel, event.noteNumber, event.velocity)
                    else notePlayer.noteOff(event.channel, event.noteNumber)
                }
            } finally {
                notePlayer.allNotesOff()
                _isPlaying.value = false
            }
        }
    }

    /** Overload for backward compatibility — wraps a single track in a list. */
    fun playWithTiming(
        melodyTrack: MelodyTrack,
        scope: CoroutineScope,
        tempo: Float = 1.0f
    ) = playWithTiming(listOf(melodyTrack), scope, tempo)

    /**
     * Drives note-on/off from an external game clock.
     * Notes play at their natural song position — visual offset handles sync.
     * Handles multiple tracks polyphonically — each track is treated as a separate voice/channel.
     * Tracks per-NoteEvent identity so that repeated same-pitch notes are properly
     * released before re-attack even when both fall within a single frame.
     */
    fun updateGameTime(gameTimeSeconds: Double, tracks: List<MelodyTrack>) {
        if (!notePlayer.isReady) return

        val effectiveTime = gameTimeSeconds

        // Collect all notes that should be active at the current time,
        // keyed by compound key, tracking the originating NoteEvent.
        val nextActive = mutableMapOf<Int, NoteEvent>()
        for (track in tracks) {
            val channel = track.channel
            for (note in track.notes) {
                val start = track.noteStartSeconds(note)
                val end = start + track.noteDurationSeconds(note)
                if (effectiveTime >= start && effectiveTime < end) {
                    val key = noteKey(channel, note.noteNumber)
                    if (key !in nextActive) {
                        nextActive[key] = note
                    }
                }
            }
        }

        // Turn off any previously-active notes that are no longer active,
        // or whose NoteEvent has changed (same key, different event — repeated note).
        for ((key, currentNote) in activeSynthNotes) {
            val nextNote = nextActive[key]
            if (nextNote == null || nextNote !== currentNote) {
                notePlayer.noteOff(key shr 7, key and 0x7F)
            }
        }

        // Turn on new notes, or re-attack notes whose NoteEvent changed.
        for ((key, note) in nextActive) {
            val currentNote = activeSynthNotes[key]
            if (currentNote == null || currentNote !== note) {
                notePlayer.noteOn(key shr 7, note.noteNumber, note.velocity.coerceIn(1, 127))
            }
        }

        activeSynthNotes.clear()
        activeSynthNotes.putAll(nextActive)
    }

    fun setTotalAudioDelay(delayMs: Long) {
        totalAudioDelayMs = delayMs.coerceIn(0, 500)
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        _isPlaying.value = false
        resetNoteTracking()
    }

    fun resetGameSync() {
        resetNoteTracking()
    }

    private fun resetNoteTracking() {
        activeSynthNotes.clear()
        if (notePlayer.isReady) notePlayer.allNotesOff()
    }

    companion object {
        private fun noteKey(channel: Int, note: Int): Int = (channel shl 7) or (note and 0x7F)
    }
}

private data class TimedEvent(
    val timeMs: Long,
    val noteNumber: Int,
    val isOn: Boolean,
    val velocity: Int,
    val channel: Int = 0
)
