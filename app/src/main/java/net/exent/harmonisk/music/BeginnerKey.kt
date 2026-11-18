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
        val lowestMidi = instrument.tuning.first()
        val comparator = compareBy<Candidate>(
            { it.belowLowest },
            { it.unplayable },
            { it.cost },
            { abs(it.shift) }
        )
        return (-Transpose.MAX_SEMITONES..Transpose.MAX_SEMITONES)
            .asSequence()
            .map { shift -> candidate(pitches, writtenKey, shift, instrument, preferOpenStrings, lowestMidi) }
            .minWithOrNull(comparator)
            ?.shift ?: 0
    }

    private fun candidate(
        pitches: List<Int>,
        key: KeySignature?,
        shift: Int,
        instrument: Instrument,
        preferOpenStrings: Boolean,
        lowestMidi: Int
    ): Candidate {
        val transposedPitches = pitches.map { it + shift }
        val transposedKey = key?.transpose(shift)
        val positions = bowedAlgorithm.recommendPositions(
            instrument,
            transposedPitches,
            transposedKey,
            preferOpenStrings
        )
        return Candidate(
            shift = shift,
            belowLowest = transposedPitches.count { it < lowestMidi },
            unplayable = positions.count { it == null },
            cost = cost(transposedKey, shift, transposedPitches, positions, instrument)
        )
    }

    private fun cost(
        key: KeySignature?,
        shift: Int,
        pitches: List<Int>,
        positions: List<BowedPosition?>,
        instrument: Instrument
    ): Double {
        var cost = abs(shift) * OFFSET_COST + keyBonus(key)
        val rewardedOpenStrings = mutableSetOf<Int>()
        positions.forEachIndexed { index, position ->
            if (position == null) return@forEachIndexed
            val deviation = BowedAlgorithm.fingerOffsetDeviation(
                pitches[index],
                instrument.tuning[position.stringIndex],
                position.finger,
                instrument
            ) ?: 0
            cost += abs(deviation) * DEVIATION_COST
            if (position.finger == 4) cost += FOURTH_FINGER_COST
            if (position.finger == 0 && rewardedOpenStrings.add(pitches[index])) {
                cost -= OPEN_STRING_REWARD
            }
        }
        return cost
    }

    private fun keyBonus(key: KeySignature?): Double {
        val fifths = key?.fifths ?: return 0.0
        val remoteKeyCost = (abs(fifths) - MAX_EASY_FIFTHS).coerceAtLeast(0) * REMOTE_KEY_COST
        return remoteKeyCost + when (fifths) {
            2 -> -D_MAJOR_REWARD
            3 -> -A_MAJOR_REWARD
            else -> 0.0
        }
    }

    private data class Candidate(
        val shift: Int,
        val belowLowest: Int,
        val unplayable: Int,
        val cost: Double
    )

    private companion object {
        const val OFFSET_COST = 0.5
        const val DEVIATION_COST = 3.0
        const val FOURTH_FINGER_COST = 2.0
        const val OPEN_STRING_REWARD = 4.0
        const val D_MAJOR_REWARD = 6.0
        const val A_MAJOR_REWARD = 3.0
        const val MAX_EASY_FIFTHS = 3
        const val REMOTE_KEY_COST = 3.0
    }
}
