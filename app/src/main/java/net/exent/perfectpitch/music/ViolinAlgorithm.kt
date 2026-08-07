package net.exent.perfectpitch.music

import kotlin.math.abs

enum class ViolinString {
    G, D, A, E
}

data class ViolinPosition(
    val string: ViolinString,
    val finger: Int // 0 = open string, 1-4 = fingers
)

/**
 * Recommends violin fingerings for a sequence of notes in first position.
 *
 * Finger placement (semitone offsets above a string's open pitch) follows the
 * standard first-position spacing: finger 1 = +2, finger 2 = +4, finger 3 low =
 * +5, finger 3 high = +6, finger 4 = +7. Chromatic notes are therefore playable
 * with "low" or "high" finger variants. Notes outside first position (below G3
 * or above B5) have no valid position and return null.
 */
class ViolinAlgorithm {

    private fun getAllValidPositions(targetMidi: Int): List<ViolinPosition> =
        POSITION_MAP[targetMidi] ?: emptyList()

    private fun calculateDistance(posA: ViolinPosition, posB: ViolinPosition?): Int {
        if (posB == null) return 0

        val stringDiff = abs(posA.string.ordinal - posB.string.ordinal)
        val fingerDiff = abs(posA.finger - posB.finger)

        // String crossings cost more physically than moving fingers on the same string
        return (stringDiff * 3) + fingerDiff
    }

    fun findRecommendedPosition(
        targetMidi: Int,
        previousPosition: ViolinPosition?
    ): ViolinPosition? {
        val candidates = getAllValidPositions(targetMidi)

        if (candidates.isEmpty()) return null // Note cannot be played in first position

        var bestCandidate: ViolinPosition? = null
        var lowestScore = Int.MAX_VALUE

        for (candidate in candidates) {
            var score = 0

            // 1. Proximity Cost: How far is this from the last played position?
            score += calculateDistance(candidate, previousPosition)

            // 2. Beginner Bias: Heavily reward Open Strings (finger 0)
            if (candidate.finger == 0) {
                score -= 5
            }
            // 3. Pinky Penalty: Slightly penalize 4th finger if alternatives exist
            else if (candidate.finger == 4) {
                score += 2
            }

            // Track the lowest score (best option)
            if (score < lowestScore) {
                lowestScore = score
                bestCandidate = candidate
            }
        }

        return bestCandidate
    }

    /**
     * Recommends a fingering for every note in a melody.
     *
     * Uses dynamic programming over the whole sequence so that each choice is
     * influenced by both the previous and the upcoming notes (minimizes the
     * total transition cost plus per-note open-string/pinky biases).
     * Out-of-range notes (no valid first-position fingering) map to null.
     */
    fun recommendPositions(notes: List<Int>): List<ViolinPosition?> {
        if (notes.isEmpty()) return emptyList()

        val candidateLists = notes.map { getAllValidPositions(it) }
        val result = arrayOfNulls<ViolinPosition>(notes.size)
        val playableIndices = notes.indices.filter { candidateLists[it].isNotEmpty() }
        if (playableIndices.isEmpty()) return result.toList()

        // DP row element: (candidate, totalCost, previous candidate row index)
        data class Entry(val position: ViolinPosition, val totalCost: Int, val prevIndex: Int)

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
        // Open string MIDI numbers: G3, D4, A4, E5
        private val OPEN_MIDI = mapOf(
            ViolinString.G to 55,
            ViolinString.D to 62,
            ViolinString.A to 69,
            ViolinString.E to 76
        )

        // First-position playable range (inclusive)
        const val MIN_FIRST_POSITION_MIDI = 55 // G3
        const val MAX_FIRST_POSITION_MIDI = 83 // B5

        /** Finger -> semitone offset(s) above the open string (low, natural/high variants). */
        private val FINGER_OFFSETS = listOf(
            0 to listOf(0),
            1 to listOf(1, 2),
            2 to listOf(3, 4),
            3 to listOf(5, 6),
            4 to listOf(7)
        )

        private val POSITION_MAP: Map<Int, List<ViolinPosition>> = buildMap {
            for (string in ViolinString.entries) {
                val open = OPEN_MIDI.getValue(string)
                for ((finger, offsets) in FINGER_OFFSETS) {
                    for (offset in offsets) {
                        val midi = open + offset
                        if (midi in MIN_FIRST_POSITION_MIDI..MAX_FIRST_POSITION_MIDI) {
                            merge(midi, listOf(ViolinPosition(string, finger))) { a, b -> a + b }
                        }
                    }
                }
            }
        }

        /** Semitone offset of a MIDI note above the given string's open pitch. */
        fun semitoneOffset(midi: Int, string: ViolinString): Int = midi - OPEN_MIDI.getValue(string)

        /**
         * The default (natural) first-position semitone offset for a finger:
         * the placement that sits right on the string line on the board.
         * Finger 3 low (+5) is the default C, while finger 3 high (+6) is C#.
         */
        fun defaultFingerOffset(finger: Int): Int? = when (finger) {
            0 -> 0
            1 -> 2
            2 -> 4
            3 -> 5
            4 -> 7
            else -> null
        }

        /**
         * Vertical deviation (in semitones) of a note's placement from the finger's
         * default on the given string: negative = below the line, positive = above.
         */
        fun fingerOffsetDeviation(midi: Int, string: ViolinString, finger: Int): Int? =
            defaultFingerOffset(finger)?.let { semitoneOffset(midi, string) - it }

        /** The nearest string for a note that has no valid first-position fingering. */
        fun nearestString(midi: Int): ViolinString =
            ViolinString.entries.minByOrNull { abs(midi - OPEN_MIDI.getValue(it)) } ?: ViolinString.G
    }
}
