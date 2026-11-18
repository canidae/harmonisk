package net.exent.harmonisk.audio

class PitchDebouncer(
    private val requiredFrames: Int = 4,
    private val silenceFrames: Int = 12
) {

    private var stableFrequency = 0f
    private var candidateFrequency = 0f
    private var candidateFrames = 0
    private var silentFrames = 0

    fun filter(frequency: Float): Float {
        if (frequency <= 0f) {
            silentFrames++
            if (silentFrames >= silenceFrames) reset()
            return 0f
        }
        silentFrames = 0
        if (stableFrequency <= 0f) {
            stableFrequency = frequency
            candidateFrequency = frequency
            candidateFrames = requiredFrames
            return stableFrequency
        }
        if (PitchReference.matches(frequency, stableFrequency)) {
            stableFrequency = frequency
            candidateFrequency = frequency
            candidateFrames = requiredFrames
            return stableFrequency
        }
        if (PitchReference.matches(frequency, candidateFrequency)) {
            candidateFrames++
        } else {
            candidateFrequency = frequency
            candidateFrames = 1
        }
        if (candidateFrames >= requiredFrames) {
            stableFrequency = frequency
            candidateFrames = requiredFrames
        }
        return stableFrequency
    }

    fun reset() {
        stableFrequency = 0f
        candidateFrequency = 0f
        candidateFrames = 0
        silentFrames = 0
    }
}
