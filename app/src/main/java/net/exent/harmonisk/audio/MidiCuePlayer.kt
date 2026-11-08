package net.exent.harmonisk.audio

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MidiCuePlayer @Inject constructor(
    private val synthesizer: SoundFontSynthesizer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val jobs = mutableListOf<Job>()
    private var generation = 0L
    private var prepareJob: Job? = null

    fun prepare() {
        if (synthesizer.isReady || prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            withContext(Dispatchers.IO) { runCatching { synthesizer.open() } }
        }
    }

    fun play(cue: MidiCue) {
        if (cue.notes.isEmpty()) return
        val id = synchronized(lock) { ++generation }
        val job = scope.launch {
            if (!synthesizer.isReady) {
                withContext(Dispatchers.IO) { runCatching { synthesizer.open() } }
            }
            if (!synthesizer.isReady) return@launch
            cue.drumChannels.forEach { synthesizer.setChannelDrum(it) }
            cue.programs.forEach { (channel, program) ->
                synthesizer.setChannelPreset(channel, 0, program)
            }
            val events = buildList {
                cue.notes.forEach { note ->
                    add(CueEvent(note.startMs, true, note))
                    add(CueEvent(note.startMs + note.lengthMs, false, note))
                }
            }.sortedWith(compareBy({ it.at }, { it.on }))
            val start = SystemClock.elapsedRealtime()
            for (event in events) {
                val wait = start + event.at - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                if (event.on) {
                    val velocity = (event.note.velocity * CUE_GAIN).roundToInt().coerceIn(1, 127)
                    synthesizer.noteOn(event.note.channel, event.note.key, velocity)
                } else {
                    synthesizer.noteOff(event.note.channel, event.note.key)
                }
            }
        }
        synchronized(lock) { jobs += job }
        job.invokeOnCompletion {
            val last = synchronized(lock) {
                jobs -= job
                id == generation && jobs.isEmpty()
            }
            if (last) synthesizer.restoreInstrument()
        }
    }

    fun stopAll() {
        synchronized(lock) {
            generation++
            jobs.toList().also { jobs.clear() }.forEach { it.cancel() }
        }
        if (synthesizer.isReady) {
            synthesizer.allNotesOff()
            synthesizer.restoreInstrument()
        }
    }

    private data class CueEvent(val at: Int, val on: Boolean, val note: MidiCueNote)

    private companion object {
        const val CUE_GAIN = 0.68f
    }
}
