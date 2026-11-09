package net.exent.harmonisk.music

import net.exent.harmonisk.midi.KeyDetector
import net.exent.harmonisk.midi.KeySignature
import net.exent.harmonisk.midi.NoteEvent
import kotlin.math.abs

class BeginnerKey(private val bowedAlgorithm: BowedAlgorithm) {

    fun offset(
        notes: List<NoteEvent>,
        key: KeySignature?,
        instrument: Instrument,
        preferOpenStrings: Boolean = true
    ): Int {
        if (notes.isEmpty()) return 0
        val writtenKey = key ?: KeyDetector.detect(notes)
        val pitches = notes.map { it.noteNumber }
        return (-MAX_SHIFT..MAX_SHIFT).minByOrNull { shift ->
            cost(pitches, writtenKey, shift, instrument, preferOpenStrings)
        } ?: 0
    }

    private fun cost(
        pitches: List<Int>,
        key: KeySignature?,
        shift: Int,
        instrument: Instrument,
        preferOpenStrings: Boolean
    ): Double {
        val transposedKey = key?.transpose(shift)
        val positions = bowedAlgorithm.recommendPositions(
            instrument,
            pitches.map { it + shift },
            transposedKey,
            preferOpenStrings
        )
        var cost = abs(shift) * OFFSET_COST + keyBonus(transposedKey)
        val rewardedOpenStrings = mutableSetOf<Int>()
        positions.forEachIndexed { index, position ->
            if (position == null) {
                cost += OUT_OF_RANGE_COST
                return@forEachIndexed
            }
            val deviation = BowedAlgorithm.fingerOffsetDeviation(
                pitches[index] + shift,
                instrument.tuning[position.stringIndex],
                position.finger,
                instrument
            ) ?: 0
            cost += abs(deviation) * DEVIATION_COST
            if (position.finger == 4) cost += FOURTH_FINGER_COST
            if (position.finger == 0 && rewardedOpenStrings.add(pitches[index] + shift)) {
                cost -= OPEN_STRING_REWARD
            }
        }
        return cost
    }

    private fun keyBonus(key: KeySignature?): Double = when (key?.fifths) {
        2 -> -D_MAJOR_REWARD
        3 -> -A_MAJOR_REWARD
        else -> 0.0
    }

    private companion object {
        const val MAX_SHIFT = 6
        const val OFFSET_COST = 0.5
        const val OUT_OF_RANGE_COST = 40.0
        const val DEVIATION_COST = 3.0
        const val FOURTH_FINGER_COST = 2.0
        const val OPEN_STRING_REWARD = 4.0
        const val D_MAJOR_REWARD = 6.0
        const val A_MAJOR_REWARD = 3.0
    }
}
