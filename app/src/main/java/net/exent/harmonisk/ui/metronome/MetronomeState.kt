package net.exent.harmonisk.ui.metronome

const val METRONOME_MIN_BPM = 30
const val METRONOME_MAX_BPM = 250
const val METRONOME_MAX_BEATS = 12

data class MetronomeState(
    val bpm: Int = 100,
    val beatsPerBar: Int = 4,
    val accentBeat: Int = 0,
    val mutedBeats: Set<Int> = emptySet(),
    val soundEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val currentBeat: Int = 0,
    val pulse: Long = 0L
)
