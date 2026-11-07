package net.exent.harmonisk.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchFusionTest {

    private val none = PitchDetectionResult.NONE

    @Test
    fun usesYinWhenCrepeIsNotConfident() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(196f, 0.3f),
            corrected = none,
            yin = PitchDetectionResult(65.4f, 0.99f)
        )
        assertEquals(65.4f, result.frequency, 0.01f)
    }

    @Test
    fun returnsNoneWhenNeitherDetectorIsConfident() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(196f, 0.3f),
            corrected = none,
            yin = none
        )
        assertEquals(-1f, result.frequency, 0f)
    }

    @Test
    fun usesYinWhenItFindsALowerFundamental() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(196f, 0.9f),
            corrected = PitchDetectionResult(98f, 0.9f),
            yin = PitchDetectionResult(65.4f, 0.99f)
        )
        assertEquals(65.4f, result.frequency, 0.01f)
    }

    @Test
    fun keepsCorrectedFrequencyWhenYinIsHigher() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(196f, 0.9f),
            corrected = PitchDetectionResult(98f, 0.9f),
            yin = PitchDetectionResult(207f, 0.99f)
        )
        assertEquals(98f, result.frequency, 0.01f)
    }

    @Test
    fun ignoresLowerYinWithoutSubharmonicEvidence() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(438f, 0.9f),
            corrected = PitchDetectionResult(438f, 0.9f),
            yin = PitchDetectionResult(219f, 0.98f),
            trustLowerYin = false
        )
        assertEquals(438f, result.frequency, 0.01f)
    }

    @Test
    fun keepsCrepePrecisionWhenDetectorsAgree() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(440f, 0.9f),
            corrected = PitchDetectionResult(440f, 0.9f),
            yin = PitchDetectionResult(443f, 0.99f)
        )
        assertEquals(440f, result.frequency, 0.01f)
    }

    @Test
    fun usesYinWhenTheCorrectedFrequencyIsOutOfRange() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(98f, 0.8f),
            corrected = none,
            yin = PitchDetectionResult(65.4f, 0.99f)
        )
        assertEquals(65.4f, result.frequency, 0.01f)
    }

    @Test
    fun usesYinWhenCrepeIsNotConfidentEvenForHighPitches() {
        val result = PitchFusion.combine(
            crepe = PitchDetectionResult(1318f, 0.4f),
            corrected = none,
            yin = PitchDetectionResult(1325f, 0.97f)
        )
        assertEquals(1325f, result.frequency, 0.01f)
    }
}
