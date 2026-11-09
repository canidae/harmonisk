package net.exent.harmonisk.music

import net.exent.harmonisk.midi.KeySignature
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class BowedPosition(
    val stringIndex: Int,
    val finger: Int
)

class BowedAlgorithm {

    private fun getAllValidPositions(
        targetMidi: Int,
        instrument: Instrument,
        key: KeySignature?,
        preferOpenStrings: Boolean
    ): List<BowedPosition> {
        val positions = positionMap(instrument)[targetMidi] ?: return emptyList()
        val spelled = if (!isFlatSpelled(targetMidi, key)) positions else positions.map { position ->
            if (position.finger == 3 && semitoneOffset(targetMidi, instrument.tuning[position.stringIndex]) == 6) {
                BowedPosition(position.stringIndex, 4)
            } else {
                position
            }
        }
        if (targetMidi !in openStringMidis(instrument)) return spelled
        if (preferOpenStrings) return spelled.filter { it.finger == 0 }
        return spelled.filter { it.finger != 0 }.ifEmpty { spelled.filter { it.finger == 0 } }
    }

    private fun isFlatSpelled(midi: Int, key: KeySignature?): Boolean {
        if (key == null) return true
        if (KeySignature.keyAlteration(midi - 1, key.fifths) > 0) return false
        if (KeySignature.keyAlteration(midi + 1, key.fifths) < 0) return true
        return key.usesFlats
    }

    private fun calculateDistance(posA: BowedPosition, posB: BowedPosition?): Int {
        if (posB == null) return 0

        val stringDiff = abs(posA.stringIndex - posB.stringIndex)
        val fingerDiff = abs(posA.finger - posB.finger)

        return (stringDiff * 3) + fingerDiff
    }

    fun recommendPositions(
        instrument: Instrument,
        notes: List<Int>,
        key: KeySignature? = null,
        preferOpenStrings: Boolean = true
    ): List<BowedPosition?> {
        if (notes.isEmpty()) return emptyList()

        val candidateLists = notes.map {
            getAllValidPositions(it, instrument, key, preferOpenStrings)
        }
        val result = arrayOfNulls<BowedPosition>(notes.size)
        val playableIndices = notes.indices.filter { candidateLists[it].isNotEmpty() }
        if (playableIndices.isEmpty()) return result.toList()

        data class Entry(val position: BowedPosition, val totalCost: Int, val prevIndex: Int)

        var prevRow: List<Entry> = emptyList()
        val rows = mutableListOf<MutableList<Entry>>()

        for (i in playableIndices) {
            val row = mutableListOf<Entry>()
            for (candidate in candidateLists[i]) {
                val bias = when (candidate.finger) {
                    0 -> -5
                    4 -> 2
                    else -> 0
                }
                val best = if (prevRow.isEmpty()) {
                    Entry(candidate, bias, -1)
                } else {
                    prevRow.withIndex()
                        .minByOrNull { (_, entry) ->
                            entry.totalCost + calculateDistance(candidate, entry.position)
                        }
                        ?.let { (idx, entry) ->
                            Entry(candidate, entry.totalCost + calculateDistance(candidate, entry.position) + bias, idx)
                        } ?: Entry(candidate, bias, -1)
                }
                row.add(best)
            }
            rows.add(row)
            prevRow = row
        }

        var chosenIndex = prevRow.indices.minByOrNull { prevRow[it].totalCost } ?: 0
        for (k in playableIndices.indices.reversed()) {
            val entry = rows[k][chosenIndex]
            result[playableIndices[k]] = entry.position
            if (entry.prevIndex < 0) break
            chosenIndex = entry.prevIndex
        }

        return result.toList()
    }

    companion object {
        private val VIOLIN_FINGER_OFFSETS = listOf(
            0 to listOf(0),
            1 to listOf(1, 2),
            2 to listOf(3, 4),
            3 to listOf(5, 6),
            4 to listOf(7)
        )

        private val CELLO_FINGER_OFFSETS = listOf(
            0 to listOf(0),
            1 to listOf(1, 2),
            2 to listOf(3),
            3 to listOf(4),
            4 to listOf(5)
        )

        private val POSITION_MAPS = ConcurrentHashMap<List<Int>, Map<Int, List<BowedPosition>>>()
        private val OPEN_STRING_MIDIS = ConcurrentHashMap<List<Int>, Set<Int>>()

        private fun positionMap(instrument: Instrument): Map<Int, List<BowedPosition>> =
            POSITION_MAPS.getOrPut(instrument.tuning) {
                val lowestMidi = instrument.tuning.first()
                val highestMidi = instrument.highestFirstPositionMidi
                val fingerOffsets = if (instrument == Instrument.CELLO) {
                    CELLO_FINGER_OFFSETS
                } else {
                    VIOLIN_FINGER_OFFSETS
                }
                buildMap {
                    instrument.tuning.forEachIndexed { stringIndex, open ->
                        for ((finger, offsets) in fingerOffsets) {
                            for (offset in offsets) {
                                val midi = open + offset
                                if (midi in lowestMidi..highestMidi) {
                                    merge(midi, listOf(BowedPosition(stringIndex, finger))) { a, b -> a + b }
                                }
                            }
                        }
                    }
                }
            }

        private fun openStringMidis(instrument: Instrument): Set<Int> =
            OPEN_STRING_MIDIS.getOrPut(instrument.tuning) { instrument.tuning.drop(1).toSet() }

        fun semitoneOffset(midi: Int, openMidi: Int): Int = midi - openMidi

        fun defaultFingerOffset(instrument: Instrument, finger: Int): Int? = when (finger) {
            0 -> 0
            1 -> 2
            2 -> if (instrument == Instrument.CELLO) 3 else 4
            3 -> if (instrument == Instrument.CELLO) 4 else 5
            4 -> if (instrument == Instrument.CELLO) 5 else 7
            else -> null
        }

        fun fingerOffsetDeviation(
            midi: Int,
            openMidi: Int,
            finger: Int,
            instrument: Instrument
        ): Int? = defaultFingerOffset(instrument, finger)?.let { semitoneOffset(midi, openMidi) - it }
    }
}
