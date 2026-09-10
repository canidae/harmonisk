package net.exent.perfectpitch.audio

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.PitchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * Measures how much the tuner needle moves while a note is held, running the real CREPE model on
 * the device so the detector noise is genuine rather than assumed.
 *
 * Input is a sustained instrument-like tone at a known detune, which makes both halves of the
 * question answerable at once: how much the needle shakes, and whether the reading it settles on
 * is actually the pitch that was played. Each filter is also passed through a model of the gauge's
 * own tween animation, so the figures describe what appears on screen.
 */
@RunWith(AndroidJUnit4::class)
class TunerNeedleStabilityTest {

    companion object {
        private const val TAG = "TunerStability"
        private const val SECONDS = 6.0
        private const val GAUGE_TWEEN_SECONDS = 0.1
        private const val DETUNE_CENTS = 13.0
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val noteUtils = NoteUtils()

    private val processor by lazy {
        PitchProcessor(AudioCaptureService(context), CrepePitchDetector(context), noteUtils)
    }

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @Test
    fun heldNoteNeedleIsSteadierWithoutShiftingTheReading() {
        val capture = detect(TunerAudioFixture.tone(NoteUtils.midiToFrequency(69, 440f).toDouble(), SECONDS))

        val variants = linkedMapOf(
            "raw" to capture.results.map { raw(it) },
            "legacy" to smooth(capture, LegacySmoother()),
            "stabilised" to smooth(capture, StabilizerSmoother()),
            "legacy+gauge" to tween(smooth(capture, LegacySmoother()), capture.frameSeconds),
            "stabilised+gauge" to tween(smooth(capture, StabilizerSmoother()), capture.frameSeconds)
        )

        // Skip the first second so every filter has settled before being judged.
        val settle = (capture.results.size / SECONDS).toInt()
        val wobble = mutableMapOf<String, Double>()
        val travel = mutableMapOf<String, Double>()

        for ((label, semitones) in variants) {
            val cents = semitones.drop(settle).filterNotNull().map { it * 100.0 }
            val mean = cents.average()
            val deviation = sqrt(cents.sumOf { (it - mean) * (it - mean) } / cents.size)
            val steps = (1 until cents.size).map { abs(cents[it] - cents[it - 1]) }.sorted()
            wobble[label] = deviation
            travel[label] = steps[steps.size * 95 / 100]
            Log.i(
                TAG,
                "%-16s wobble=%.2fct travelP95=%.2fct travelMax=%.2fct reading=%+.2fct"
                    .format(label, deviation, steps[steps.size * 95 / 100], steps.last(), mean)
            )
        }

        val legacyWobble = wobble.getValue("legacy+gauge")
        val stabilisedWobble = wobble.getValue("stabilised+gauge")
        Log.i(
            TAG,
            "as displayed: wobble legacy=%.2fct stabilised=%.2fct (%.0f%% of legacy), " +
                "travelP95 legacy=%.2fct stabilised=%.2fct"
                .format(
                    legacyWobble, stabilisedWobble, stabilisedWobble * 100 / legacyWobble,
                    travel.getValue("legacy+gauge"), travel.getValue("stabilised+gauge")
                )
        )

        assertTrue(
            "displayed needle wobble should drop by at least a third " +
                "(legacy=%.2fct stabilised=%.2fct)".format(legacyWobble, stabilisedWobble),
            stabilisedWobble < legacyWobble * 0.67
        )
    }

    @Test
    fun smoothingDoesNotShiftTheReportedPitch() {
        for (detune in listOf(0.0, DETUNE_CENTS, -22.0)) {
            val freq = NoteUtils.midiToFrequency(69, 440f).toDouble() * Math.pow(2.0, detune / 1200.0)
            val capture = detect(TunerAudioFixture.tone(freq, SECONDS))
            val settle = (capture.results.size / SECONDS).toInt()

            val rawMean = capture.results.drop(settle).mapNotNull { raw(it) }.map { it * 100.0 }.average()
            val filtered = tween(smooth(capture, StabilizerSmoother()), capture.frameSeconds)
            val filteredMean = filtered.drop(settle).filterNotNull().map { it * 100.0 }.average()

            Log.i(
                TAG,
                "detune %+6.1fct -> raw reads %+6.2fct, stabilised reads %+6.2fct"
                    .format(detune, rawMean, filteredMean)
            )
            assertEquals(
                "smoothing must not shift the reading at %+.0fct".format(detune),
                rawMean, filteredMean, 0.5
            )
        }
    }

    @Test
    fun noteChangeSettlesPromptly() {
        val a4 = NoteUtils.midiToFrequency(69, 440f).toDouble()
        val capture = detect(TunerAudioFixture.toneChange(a4, a4 * 2.0, SECONDS))
        val changeFrame = capture.results.size / 2
        val frameMs = capture.frameSeconds * 1000

        val legacyRaw = settleFrames(smooth(capture, LegacySmoother()), changeFrame)
        val stabilisedRaw = settleFrames(smooth(capture, StabilizerSmoother()), changeFrame)
        Log.i(
            TAG,
            "smoothing alone settles the octave in legacy=%.0fms stabilised=%.0fms"
                .format(legacyRaw * frameMs, stabilisedRaw * frameMs)
        )
        assertTrue(
            "the stabiliser must not slow note changes down (legacy=%d frames stabilised=%d frames)"
                .format(legacyRaw, stabilisedRaw),
            stabilisedRaw <= legacyRaw
        )
        assertTrue(
            "smoothing alone must settle an octave within 250ms (was %.0fms)".format(stabilisedRaw * frameMs),
            stabilisedRaw * frameMs < 250
        )

        // The gauge's own animation sits after the filter, so also check what reaches the screen.
        for (tweenMs in listOf(100.0, 50.0, 25.0)) {
            val legacy = settleFrames(
                tween(smooth(capture, LegacySmoother()), capture.frameSeconds, tweenMs / 1000), changeFrame
            )
            val stabilised = settleFrames(
                tween(smooth(capture, StabilizerSmoother()), capture.frameSeconds, tweenMs / 1000), changeFrame
            )
            Log.i(
                TAG,
                "as displayed with a %.0fms gauge: legacy=%.0fms stabilised=%.0fms"
                    .format(tweenMs, legacy * frameMs, stabilised * frameMs)
            )
        }
    }

    /** First frame from [from] at which the value sits on the octave and stays there. */
    private fun settleFrames(values: List<Double?>, from: Int): Int {
        val frame = (from until values.size).firstOrNull { i ->
            val value = values[i] ?: return@firstOrNull false
            abs(value - 12.0) < 0.05 &&
                (i + 3 until minOf(i + 8, values.size)).all {
                    abs((values[it] ?: Double.MAX_VALUE) - 12.0) < 0.05
                }
        }
        return if (frame == null) Int.MAX_VALUE else frame - from
    }

    @Test
    fun realMicrophoneLoopbackIsSteadier() {
        val wobble = mutableMapOf<String, MutableList<Double>>()

        // Average over several notes: a single recording is at the mercy of room noise, and the
        // effect being measured is small enough in absolute cents to be swamped by one bad take.
        for (midi in listOf(50, 57, 69)) {
            val played = TunerAudioFixture.tone(NoteUtils.midiToFrequency(midi, 440f).toDouble(), SECONDS)
            val recorded = TunerAudioFixture.playAndRecord(context, played, SECONDS - 1.0)

            val captured = recorded.fold(FloatArray(0)) { acc, chunk -> acc + chunk }
            val levelDb = AudioUtils.linearToDb(AudioUtils.rms(captured))
            Log.i(
                TAG,
                "loopback note $midi captured %.1fs of audio at %.1f dB"
                    .format(recorded.size * 0.0116, levelDb)
            )
            // Only judge filter behaviour when the microphone actually heard the tone.
            assertTrue(
                "loopback captured no signal at note $midi (%.1f dB) — check speaker/mic".format(levelDb),
                captured.isNotEmpty() && levelDb > -45f
            )

            val capture = detectChunks(recorded)
            val settle = capture.results.size / 4

            // Sweep the gauge animation duration too: it is the last smoothing stage and the main
            // source of display lag, so its length decides the stability/latency trade-off.
            for (tweenMs in listOf(0.0, 100.0, 50.0, 25.0)) {
                for ((label, smoother) in listOf(
                    "legacy" to LegacySmoother(),
                    "stabilised" to StabilizerSmoother()
                )) {
                    val smoothed = smooth(capture, smoother)
                    // A gauge duration of zero means the filter's own output, before the display
                    // animation — which turns out to absorb much of the same noise.
                    val cents = (if (tweenMs == 0.0) smoothed
                    else tween(smoothed, capture.frameSeconds, tweenMs / 1000))
                        .drop(settle).filterNotNull().map { it * 100.0 }
                    val mean = cents.average()
                    val deviation = sqrt(cents.sumOf { (it - mean) * (it - mean) } / cents.size)
                    val steps = (1 until cents.size).map { abs(cents[it] - cents[it - 1]) }.sorted()
                    wobble.getOrPut("$label@$tweenMs") { mutableListOf() }.add(deviation)
                    Log.i(
                        TAG,
                        "loopback note %d gauge=%-5.0fms %-11s wobble=%.2fct travelP95=%.2fct"
                            .format(midi, tweenMs, label, deviation, steps[steps.size * 95 / 100])
                    )
                }
            }
        }

        for (tweenMs in listOf(0.0, 100.0, 50.0, 25.0)) {
            val legacy = wobble.getValue("legacy@$tweenMs").average()
            val stabilised = wobble.getValue("stabilised@$tweenMs").average()
            Log.i(
                TAG,
                "loopback gauge=%.0fms averaged: legacy=%.3fct stabilised=%.3fct (%.0f%% of legacy)"
                    .format(tweenMs, legacy, stabilised, stabilised * 100 / legacy)
            )
        }

        val legacyFilter = wobble.getValue("legacy@0.0").average()
        val stabilisedFilter = wobble.getValue("stabilised@0.0").average()
        // Measured at the test volume, half of maximum: the quieter loopback leaves the detector
        // noisier, so the filter's advantage over the old one is smaller than it is at full volume.
        assertTrue(
            "filtered needle wobble on a real microphone signal should be well under the old " +
                "filter (legacy=%.3fct stabilised=%.3fct)".format(legacyFilter, stabilisedFilter),
            stabilisedFilter < legacyFilter * 0.75
        )

        val legacyShown = wobble.getValue("legacy@100.0").average()
        val stabilisedShown = wobble.getValue("stabilised@100.0").average()
        assertTrue(
            "what reaches the screen should still be steadier than before " +
                "(legacy=%.3fct stabilised=%.3fct)".format(legacyShown, stabilisedShown),
            stabilisedShown < legacyShown * 0.9
        )
    }

    /**
     * The detector must keep up with the microphone. If inference is slower than the 11.6 ms audio
     * hop, the capture buffer overflows, chunks are dropped, and the sliding analysis window ends
     * up stitched from non-contiguous audio — which shows up as a needle that jumps.
     */
    @Test
    fun detectionKeepsUpWithLiveCapture() {
        val captureRate = chunksPerSecond(AudioCaptureService(context).audioFlow())
        val detectionRate = chunksPerSecond(processor.pitchFlow())

        Log.i(
            TAG,
            "live rates: capture=%.1f chunks/s, detection=%.1f chunks/s (%.0f%% of capture)"
                .format(captureRate, detectionRate, detectionRate * 100 / captureRate)
        )
        assertTrue(
            "detection ran at %.0f%% of the capture rate — audio chunks are being dropped"
                .format(detectionRate * 100 / captureRate),
            detectionRate > captureRate * 0.9
        )
    }

    private fun <T> chunksPerSecond(flow: kotlinx.coroutines.flow.Flow<T>): Double {
        var count = 0
        val started = System.nanoTime()
        runBlocking { withTimeoutOrNull(3000) { flow.collect { count++ } } }
        return count / ((System.nanoTime() - started) / 1e9)
    }

    private class Capture(val results: List<PitchResult>, val frameSeconds: Double)

    /** Reads a steady signal the way the tuner would, through the real capture framing. */
    private fun detect(audio: FloatArray): Capture = detectChunks(TunerAudioFixture.chunks(audio))

    private fun detectChunks(chunks: List<FloatArray>): Capture {
        val started = System.nanoTime()
        val results = runBlocking { processor.pitchFlow(chunks.asFlow()).toList() }
        // Frames cannot arrive faster than the audio hop, however quick inference is.
        val perFrame = maxOf(
            (System.nanoTime() - started).toDouble() / results.size / 1e9,
            AudioConstants.FRAME_DURATION_MS / 1000.0
        )
        return Capture(results, perFrame)
    }

    private fun raw(result: PitchResult): Double? =
        if (!result.isValid) null else 12.0 * log2(result.frequency / 440.0)

    private fun smooth(capture: Capture, smoother: Smoother): List<Double?> =
        capture.results.map { smoother.accept(it, capture.frameSeconds) }

    /** Models `animateFloatAsState` with a tween: a fresh ramp towards each new target. */
    private fun tween(
        values: List<Double?>,
        dt: Double,
        durationSeconds: Double = GAUGE_TWEEN_SECONDS
    ): List<Double?> {
        var current = Double.NaN
        return values.map { target ->
            when {
                target == null -> {
                    current = Double.NaN
                    null
                }
                current.isNaN() -> {
                    current = target
                    current
                }
                else -> {
                    current += (target - current) * (dt / durationSeconds).coerceAtMost(1.0)
                    current
                }
            }
        }
    }

    private interface Smoother {
        fun accept(result: PitchResult, dt: Double): Double?
    }

    /** The tuner's previous smoothing: a fixed-alpha EMA in semitone space. */
    private class LegacySmoother : Smoother {
        private companion object {
            const val EMA_ALPHA = 0.3
        }

        private var smoothed = 0.0
        private var first = true

        override fun accept(result: PitchResult, dt: Double): Double? {
            if (!result.isValid) {
                first = true
                return null
            }
            val rawSemitones = 12.0 * log2(result.frequency / 440.0)
            if (first) {
                smoothed = rawSemitones
                first = false
            } else {
                smoothed = EMA_ALPHA * rawSemitones + (1 - EMA_ALPHA) * smoothed
            }
            return smoothed
        }
    }

    private class StabilizerSmoother : Smoother {
        private val stabilizer = PitchStabilizer()

        override fun accept(result: PitchResult, dt: Double): Double? {
            if (!result.isValid) {
                stabilizer.reset()
                return null
            }
            return stabilizer.accept(12.0 * log2(result.frequency / 440.0), result.confidence, dt)
        }
    }
}
