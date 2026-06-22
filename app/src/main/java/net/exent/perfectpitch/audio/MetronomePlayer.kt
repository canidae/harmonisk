package net.exent.perfectpitch.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MetronomePlayer(
    private val notePlayer: NotePlayer
) {

    companion object {
        private const val DRUM_CHANNEL = SoundFontSynthesizer.DRUM_CHANNEL
        private const val CLICK_NOTE = 85
        private const val ACCENT_NOTE = 86
        private const val VELOCITY = 100
        private const val NOTE_DURATION_MS = 40L
        private const val ACCENT_VELOCITY = 120
        private const val ACCENT_DURATION_MS = 60L
        private const val DRIFT_TOLERANCE_MS = 200L
    }

    @Volatile private var armed = false
    @Volatile private var playing = false
    @Volatile private var bpm: Float = 120f
    @Volatile private var beatsPerBar: Int = 4
    private var scheduledOffJobs = mutableListOf<Job>()
    private var beatJob: Job? = null
    private var startWallNs: Long = 0L
    private var startPositionMs: Long = 0L
    private var lastFiredBeat: Long = Long.MIN_VALUE
    private var playIntervalMs: Double = 500.0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun setEnabled(enabled: Boolean) {
        armed = enabled
        if (!enabled) {
            playing = false
            beatJob?.cancel()
            beatJob = null
            cancelScheduledOffs()
        }
    }

    fun startSync(positionMs: Long, bpm: Float, beatsPerBar: Int) {
        this.bpm = bpm
        this.beatsPerBar = beatsPerBar
        playing = armed
        if (!playing) return

        beatJob?.cancel()

        val interval = beatIntervalMs()
        if (interval <= 0.0) return
        playIntervalMs = interval

        startWallNs = System.nanoTime()
        startPositionMs = positionMs
        // Fire beat at positionMs if exactly on boundary, otherwise wait for next
        lastFiredBeat = kotlin.math.ceil(positionMs.toDouble() / interval).toLong() - 1

        beatJob = scope.launch { runBeatLoop() }
    }

    private fun resync(positionMs: Long, bpm: Float, beatsPerBar: Int) {
        this.bpm = bpm
        this.beatsPerBar = beatsPerBar
        if (!playing) return

        beatJob?.cancel()

        val interval = beatIntervalMs()
        if (interval <= 0.0) return
        playIntervalMs = interval

        startWallNs = System.nanoTime()
        startPositionMs = positionMs
        // On resync, don't re-fire the beat at positionMs
        lastFiredBeat = (positionMs / interval).toLong()

        beatJob = scope.launch { runBeatLoop() }
    }

    fun syncPosition(positionMs: Long, bpm: Float, beatsPerBar: Int) {
        this.bpm = bpm
        this.beatsPerBar = beatsPerBar
        if (armed && !playing) {
            startSync(positionMs, bpm, beatsPerBar)
            return
        }
        if (!playing) return

        val elapsedMs = (System.nanoTime() - startWallNs) / 1_000_000L
        val expectedMs = startPositionMs + elapsedMs
        val drift = kotlin.math.abs(expectedMs - positionMs)

        if (drift > DRIFT_TOLERANCE_MS) {
            resync(positionMs, bpm, beatsPerBar)
        }
    }

    fun stopSync() {
        playing = false
        beatJob?.cancel()
        beatJob = null
        cancelScheduledOffs()
    }

    fun close() {
        playing = false
        armed = false
        beatJob?.cancel()
        beatJob = null
        cancelScheduledOffs()
    }

    private suspend fun runBeatLoop() {
        val interval = playIntervalMs

        while (playing) {
            val elapsedMs = (System.nanoTime() - startWallNs) / 1_000_000L
            val currentPositionMs = startPositionMs + elapsedMs
            val currentBeat = (currentPositionMs / interval).toLong()

            if (currentBeat > lastFiredBeat) {
                for (b in lastFiredBeat + 1..currentBeat) {
                    fireClick(b)
                }
                lastFiredBeat = currentBeat
            }

            val nextMs = (lastFiredBeat + 1) * interval - currentPositionMs
            delay(maxOf(1L, nextMs.toLong()))
        }
    }

    private fun fireClick(beat: Long) {
        val isAccent = beatsPerBar > 0 && (beat % beatsPerBar) == 0L
        val note = if (isAccent) ACCENT_NOTE else CLICK_NOTE
        val vel = if (isAccent) ACCENT_VELOCITY else VELOCITY
        val durationMs = if (isAccent) ACCENT_DURATION_MS else NOTE_DURATION_MS
        notePlayer.noteOn(DRUM_CHANNEL, note, vel)
        val job = scope.launch {
            delay(durationMs)
            notePlayer.noteOff(DRUM_CHANNEL, note)
        }
        scheduledOffJobs.add(job)
    }

    private fun beatIntervalMs(): Double = 60000.0 / bpm

    private fun cancelScheduledOffs() {
        scheduledOffJobs.forEach { it.cancel() }
        scheduledOffJobs.clear()
        notePlayer.allNotesOff()
    }
}
