package net.exent.perfectpitch.music

import net.exent.perfectpitch.midi.MelodyTrack
import kotlin.math.abs

object DifficultyCalculator {

    const val ALGORITHM_VERSION = 1

    private const val R_MAX = 24.0
    private const val D_MAX = 5.0
    private const val J_MAX = 12.0

    private const val W_R = 0.3
    private const val W_D = 0.4
    private const val W_J = 0.3

    fun calculate(track: MelodyTrack): Float {
        val notes = track.notes
        if (notes.size < 2) return 0.0f

        val sortedNotes = notes.sortedWith(compareBy({ it.startTick }, { it.noteNumber }))
        val durationMs = (track.durationSeconds * 1000).coerceAtLeast(1.0)

        val range = (notes.maxOf { it.noteNumber } - notes.minOf { it.noteNumber }).toDouble()
        val density = notes.size / durationMs * 1000.0

        var jumpSum = 0.0
        for (i in 1 until sortedNotes.size) {
            jumpSum += abs(sortedNotes[i].noteNumber - sortedNotes[i - 1].noteNumber)
        }
        val jump = jumpSum / (sortedNotes.size - 1)

        val scoreR = minOf(1.0, range / R_MAX)
        val scoreD = minOf(1.0, density / D_MAX)
        val scoreJ = minOf(1.0, jump / J_MAX)

        val difficulty = W_R * scoreR + W_D * scoreD + W_J * scoreJ
        return (difficulty * 100).toInt() / 100.0f
    }
}
