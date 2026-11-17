package net.exent.harmonisk.ui.metronome

const val METRONOME_MIN_BPM = 30
const val METRONOME_MAX_BPM = 250
const val METRONOME_MAX_BEATS = 12

data class MetronomeState(
    val bpm: Int = 100,
    val beatsPerBar: Int = 4,
    val accentedBeats: Set<Int> = setOf(0),
    val mutedBeats: Set<Int> = emptySet(),
    val soundEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val currentBeat: Int = 0,
    val pulse: Long = 0L
)

fun cycleBeatState(beat: Int, accentedBeats: Set<Int>, mutedBeats: Set<Int>): Pair<Set<Int>, Set<Int>> = when {
    beat in mutedBeats -> accentedBeats to (mutedBeats - beat)
    beat in accentedBeats -> (accentedBeats - beat) to (mutedBeats + beat)
    else -> (accentedBeats + beat) to mutedBeats
}
