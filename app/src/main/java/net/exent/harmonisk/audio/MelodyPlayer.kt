package net.exent.harmonisk.audio

import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.NoteEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MelodyPlayer(
    synthesizer: NotePlayer
) {
    @Volatile private var notePlayer: NotePlayer = synthesizer
    private var playJob: Job? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val activeSynthNotes = mutableMapOf<Int, NoteEvent>()

    fun setNotePlayer(player: NotePlayer) {
        notePlayer.allNotesOff()
        notePlayer = player
    }

    fun playWithTiming(
        melodyTracks: List<MelodyTrack>,
        scope: CoroutineScope,
        leadInSeconds: Double = 0.0,
        startSeconds: Double = 0.0
    ) {
        stop()
        _isPlaying.value = true

        playJob = scope.launch {
            val sounding = mutableSetOf<Int>()
            try {
                val startTimeMs = System.currentTimeMillis()

                val events = mutableListOf<TimedEvent>()
                for (track in melodyTracks) {
                    val channel = track.channel
                    for (note in track.notes) {
                        val noteStart = track.noteStartSeconds(note)
                        val noteEnd = noteStart + track.noteDurationSeconds(note)
                        if (noteEnd <= startSeconds) continue
                        val localStart = (noteStart - startSeconds).coerceAtLeast(0.0) + leadInSeconds
                        val localEnd = (noteEnd - startSeconds).coerceAtLeast(0.0) + leadInSeconds
                        val startMs = (localStart * 1000).toLong()
                        val endMs = (localEnd * 1000).toLong()
                        events.add(TimedEvent(startMs, note.noteNumber, true, note.velocity.coerceIn(1, 127), channel))
                        events.add(TimedEvent(endMs, note.noteNumber, false, 0, channel))
                    }
                }
                events.sortBy { it.timeMs }

                for (event in events) {
                    if (!isActive) break
                    val waitMs = startTimeMs + event.timeMs - System.currentTimeMillis()
                    if (waitMs > 0) delay(waitMs)
                    if (event.isOn) {
                        notePlayer.noteOn(event.channel, event.noteNumber, event.velocity)
                        sounding.add(noteKey(event.channel, event.noteNumber))
                    } else {
                        notePlayer.noteOff(event.channel, event.noteNumber)
                        sounding.remove(noteKey(event.channel, event.noteNumber))
                    }
                }
            } finally {
                sounding.releaseWith(notePlayer)
                _isPlaying.value = false
            }
        }
    }

    fun updateGameTime(gameTimeSeconds: Double, tracks: List<MelodyTrack>) {
        if (!notePlayer.isReady) return

        val nextActive = mutableMapOf<Int, NoteEvent>()
        for (track in tracks) {
            val channel = track.channel
            for (note in track.notes) {
                val start = track.noteStartSeconds(note)
                val end = start + track.noteDurationSeconds(note)
                if (gameTimeSeconds >= start && gameTimeSeconds < end) {
                    val key = noteKey(channel, note.noteNumber)
                    if (key !in nextActive) {
                        nextActive[key] = note
                    }
                }
            }
        }

        for ((key, currentNote) in activeSynthNotes) {
            val nextNote = nextActive[key]
            if (nextNote == null || nextNote !== currentNote) {
                notePlayer.noteOff(key shr 7, key and 0x7F)
            }
        }

        for ((key, note) in nextActive) {
            val currentNote = activeSynthNotes[key]
            if (currentNote == null || currentNote !== note) {
                notePlayer.noteOn(key shr 7, note.noteNumber, note.velocity.coerceIn(1, 127))
            }
        }

        activeSynthNotes.clear()
        activeSynthNotes.putAll(nextActive)
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
        activeSynthNotes.keys.releaseWith(notePlayer)
        activeSynthNotes.clear()
    }

    private fun Set<Int>.releaseWith(player: NotePlayer) {
        for (key in this) player.noteOff(key shr 7, key and 0x7F)
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
