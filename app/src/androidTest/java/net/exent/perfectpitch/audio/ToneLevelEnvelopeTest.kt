package net.exent.perfectpitch.audio

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.exent.perfectpitch.music.NoteUtils
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures the level envelope of a note as the microphone hears it, because Play by Melody has to
 * tell a tone the player released from a tone they are still holding, and pitch detection keeps
 * reporting the ringing tail of a released note.
 *
 * Each fixture is played through the device's own speaker and recorded back through the real capture
 * path, so the numbers include the speaker, the room and the microphone's own processing. The same
 * recording is then run through the production pitch flow to see whether the detector is still
 * reporting the released tone, which is what hides the pause from the game.
 */
@RunWith(AndroidJUnit4::class)
class ToneLevelEnvelopeTest {

    private companion object {
        private const val TAG = "ToneLevel"

        private const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE

        private const val D4_MIDI = 62

        /** Held-tone fixture length. */
        private const val HELD_SECONDS = 2.4

        /** How long the recording keeps running after the fixture ends. */
        private const val LISTEN_EXTRA_SECONDS = 0.4

        /** Level of the lingering tail in the released-note fixtures, relative to the played note. */
        private const val TAIL_DB = -18.0

        /** Trailing window whose mean is the level a drop is measured against. */
        private const val REFERENCE_MS = SoundingToneGate.SLOW_DROP_MS

        /** Time one capture hop advances, as the gate measures it. */
        private const val HOP_MS_F = AudioConstants.BUFFER_SIZE_SAMPLES * 1000.0 / AudioConstants.SAMPLE_RATE

        /** Frame span of the gate's two drop windows, in hops. */
        private val FAST_HOPS = (SoundingToneGate.FAST_DROP_MS / HOP_MS_F).roundToInt()
        private val SLOW_HOPS = (SoundingToneGate.SLOW_DROP_MS / HOP_MS_F).roundToInt()

        /** How much room a held tone must leave under the gate's thresholds. */
        private const val HOLD_MARGIN_DB = 2.0

        /** A released note has to keep the detector going at least this long to hide a pause. */
        private const val MIN_LINGER_MS = 100.0

        private const val SILENT_DB = -60.0

        /** Frames skipped at the start of a fixture, where the analysis window is still filling up. */
        private const val ATTACK_SKIP_HOPS = 10
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val noteUtils = NoteUtils()

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @Test
    fun heldToneLevelStaysPut() {
        val audio = enveloped(HELD_SECONDS, listOf(0.0 to 0.0, HELD_SECONDS to 0.0))
        val capture = capture(audio, HELD_SECONDS + LISTEN_EXTRA_SECONDS)
        val levels = capture.levels

        Log.i(TAG, "=== Held D4, ${"%.2f".format(HELD_SECONDS)}s on ${android.os.Build.MODEL} ===")
        trace(capture)
        // Only the part of the fixture the player actually holds: the attack and the release are the
        // envelope's own ramps, not a level the tone sat at.
        val steady = stableRegion(levels.size)
        val held = levels.slice(steady.first, steady.last).filter { it > SILENT_DB }
        val fastDrop = maxShortDrop(levels, steady)
        val slowDrop = maxDrop(levels, REFERENCE_MS, steady)
        Log.i(TAG, "held: frames=${held.size} p5=${"%.1f".format(percentile(held, 5))} " +
                "p50=${"%.1f".format(percentile(held, 50))} " +
                "p95=${"%.1f".format(percentile(held, 95))} min=${"%.1f".format(held.min())} " +
                "maxDropOver${SoundingToneGate.FAST_DROP_MS.toInt()}ms=${"%.1f".format(fastDrop)}dB " +
                "maxDropVsTrailing${REFERENCE_MS.toInt()}ms=${"%.1f".format(slowDrop)}dB")

        assertTrue(
            "a held tone must not move enough to read as a release: dropped " +
                    "${"%.1f".format(fastDrop)}dB over ${SoundingToneGate.FAST_DROP_MS.toInt()}ms",
            fastDrop < SoundingToneGate.FAST_DROP_DB - HOLD_MARGIN_DB
        )
        assertTrue(
            "a held tone must not fade enough to read as released: dropped " +
                    "${"%.1f".format(slowDrop)}dB below its own trailing mean",
            slowDrop < SoundingToneGate.SLOW_DROP_DB - HOLD_MARGIN_DB
        )
    }

    @Test
    fun releasedToneKeepsBeingDetected() {
        val stopSeconds = 1.2
        val audio = enveloped(
            stopSeconds + 1.4,
            listOf(0.0 to 0.0, stopSeconds to 0.0, stopSeconds + 0.03 to SILENT_DB)
        )
        val capture = capture(audio, stopSeconds + 1.4)
        val levels = capture.levels
        val stopHop = (stopSeconds * 1000 / AudioConstants.FRAME_DURATION_MS).toInt()

        Log.i(TAG, "=== D4 released after ${"%.2f".format(stopSeconds)}s, room tail recorded ===")

        val before = levels.slice(stopHop - 8, stopHop).filter { it > SILENT_DB }
        val after = levels.drop(stopHop)
        val floor = after.filter { it > SILENT_DB }.minOrNull()
        // The pause the game has to see, by each of the two ways of seeing it: how long the detector
        // goes on reporting the released pitch, and when the level has fallen far enough to be a
        // release. Frames the detector drops along the way do not count: the game needs a run of
        // silence long enough to be an articulation, so the detector only gives the note up when it
        // stops reporting the pitch for good.
        val firstDropoutHop = (stopHop until capture.onPitch.size)
            .firstOrNull { !capture.onPitch[it] } ?: capture.onPitch.size
        val givesUpHop = (capture.onPitch.indexOfLast { it } + 1)
            .coerceAtMost(capture.onPitch.size)
        val givesUpMs = givesUpHop * AudioConstants.FRAME_DURATION_MS - stopSeconds * 1000
        val releaseHop = firstReleaseHop(levels, stopHop) ?: levels.size
        Log.i(TAG, "release: held=${"%.1f".format(before.average())}dB floor=" +
                "${floor?.let { "%.1f".format(it) } ?: "silent"}dB " +
                "dropOver${SoundingToneGate.FAST_DROP_MS.toInt()}ms=${"%.1f".format(maxDropOver(levels, stopHop until levels.size, FAST_HOPS))}dB " +
                "dropOver${SoundingToneGate.SLOW_DROP_MS.toInt()}ms=${"%.1f".format(maxDropOver(levels, stopHop until levels.size, SLOW_HOPS))}dB " +
                "firstDropoutAfter=${"%.0f".format(firstDropoutHop * AudioConstants.FRAME_DURATION_MS - stopSeconds * 1000)}ms " +
                "detectorGivesUpAfter=${"%.0f".format(givesUpMs)}ms " +
                "levelReleaseAfter=${"%.0f".format(releaseHop * AudioConstants.FRAME_DURATION_MS - stopSeconds * 1000)}ms")
        trace(capture)

        assertTrue(
            "the released note must go on being reported as pitch, or nothing hides the pause",
            givesUpMs >= MIN_LINGER_MS
        )
        assertTrue(
            "the level must show the release: dropped " +
                    "${"%.1f".format(maxDropOver(levels, stopHop until levels.size, FAST_HOPS))}dB over " +
                    "${SoundingToneGate.FAST_DROP_MS.toInt()}ms and " +
                    "${"%.1f".format(maxDropOver(levels, stopHop until levels.size, SLOW_HOPS))}dB over " +
                    "${SoundingToneGate.SLOW_DROP_MS.toInt()}ms, against thresholds of " +
                    "${SoundingToneGate.FAST_DROP_DB.toInt()} and ${SoundingToneGate.SLOW_DROP_DB.toInt()}dB",
            releaseHop < levels.size
        )
        assertTrue(
            "the level must show the release before the detector gives the pitch up " +
                    "(${"%.0f".format(releaseHop * AudioConstants.FRAME_DURATION_MS - stopSeconds * 1000)}ms " +
                    "vs ${"%.0f".format(givesUpMs)}ms after the note stopped)",
            releaseHop < givesUpHop
        )
    }

    /**
     * What happens to a tail held quietly at [TAIL_DB] below the note. It measures the path rather
     * than the gate: somewhere in the phone's own speaker and capture chain a sustained level step is
     * levelled out again within a few frames, so a lingering tail cannot be staged through the
     * speaker. Play by Melody's own fixtures inject the level instead (see NotePauseDetectionTest),
     * where a released note left ringing 12 dB down is what the gate has to see through.
     */
    @Test
    fun aSustainedQuietTailIsLevelledOutByThePath() {
        val stepSeconds = 0.8
        val backSeconds = 1.7
        val audio = enveloped(
            backSeconds + 0.6,
            listOf(
                0.0 to 0.0,
                stepSeconds to 0.0,
                stepSeconds + 0.05 to TAIL_DB,
                backSeconds to TAIL_DB,
                backSeconds + 0.05 to 0.0
            )
        )
        val capture = capture(audio, backSeconds + 0.6)
        val levels = capture.levels
        val stepHop = (stepSeconds * 1000 / AudioConstants.FRAME_DURATION_MS).toInt()
        val backHop = (backSeconds * 1000 / AudioConstants.FRAME_DURATION_MS).toInt()

        Log.i(TAG, "=== D4 held, then a ${TAIL_DB.toInt()}dB quiet tail, then the same note again ===")
        trace(capture)

        val sounded = levels.slice(stepHop - 8, stepHop).filter { it > SILENT_DB }.average()
        val tail = levels.slice(backHop - 8, backHop).filter { it > SILENT_DB }
        val tailOnPitch = capture.onPitch.subList(stepHop + 8, backHop - 8).count { it }
        Log.i(TAG, "tail: sounded=${"%.1f".format(sounded)}dB tail=" +
                "${"%.1f".format(tail.average())}dB (${"%.1f".format(sounded - tail.average())}dB down) " +
                "maxDrop=${"%.1f".format(maxDrop(levels, REFERENCE_MS))}dB " +
                "onPitchDuringTail=$tailOnPitch/${backHop - stepHop - 16}: the path levelled the " +
                "quiet tail back up, so the level gate cannot be measured this way")
    }

    /** One captured fixture: the level of the analysis window and whether the detector was on pitch. */
    private class Capture(val levels: DoubleArray, val onPitch: List<Boolean>)

    private fun capture(audio: FloatArray, listenSeconds: Double): Capture {
        val chunks = TunerAudioFixture.playAndRecord(context, audio, listenSeconds)
        val levels = windowLevels(chunks)
        val results = runBlocking {
            PitchProcessor(
                audioCaptureService = AudioCaptureService(context),
                crepeDetector = CrepePitchDetector(context),
                noteUtils = noteUtils
            ).pitchFlow(chunks.asFlow()).toList()
        }
        val onPitch = results.map { result ->
            result.isValid &&
                    abs(noteUtils.frequencyToCents(result.frequency, NoteUtils.midiToFrequency(D4_MIDI, 440f))) <= 50f
        }
        return Capture(levels, onPitch)
    }

    /** Level of the production analysis window per hop, i.e. what the silence gate sees. */
    private fun windowLevels(chunks: List<FloatArray>): DoubleArray {
        val hop = AudioCaptureService.BUFFER_SIZE_SAMPLES
        val window = PitchProcessor.ANALYSIS_WINDOW_SIZE
        val samples = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        val hops = (samples.size - window) / hop
        return DoubleArray(hops.coerceAtLeast(0)) { index ->
            val from = index * hop
            var sum = 0.0
            for (i in from until from + window) sum += samples[i] * samples[i]
            AudioUtils.linearToDb(sqrt(sum / window).toFloat()).toDouble()
        }
    }

    private fun trace(capture: Capture) {
        val step = 4
        val lines = capture.levels.indices.step(step).chunked(12).map { group ->
            group.joinToString(" ") { index ->
                val seconds = index * AudioConstants.FRAME_DURATION_MS / 1000.0
                val pitch = capture.onPitch.getOrNull(index)?.let { if (it) "." else "x" } ?: " "
                "%4.2fs=%5.1f%s".format(seconds, capture.levels[index], pitch)
            }
        }
        for (line in lines) Log.i(TAG, line)
    }

    /** Hops of the held fixture that are the tone and nothing else: no attack, no release. */
    private fun stableRegion(size: Int): IntRange =
        (ATTACK_SKIP_HOPS * 3)..(size - ATTACK_SKIP_HOPS * 3)

    /**
     * First hop where the level has fallen as far as [SoundingToneGate] needs to call the note
     * released, window for window: the same drops over the same spans the gate watches.
     */
    private fun firstReleaseHop(levels: DoubleArray, fromHop: Int): Int? =
        (fromHop until levels.size).firstOrNull { hop ->
            val fast = previous(levels, hop, FAST_HOPS)
            val slow = previous(levels, hop, SLOW_HOPS)
            (!fast.isNaN() && fast - levels[hop] >= SoundingToneGate.FAST_DROP_DB) ||
                    (!slow.isNaN() && slow - levels[hop] >= SoundingToneGate.SLOW_DROP_DB)
        }

    /** Biggest drop over [hops] within [range]. */
    private fun maxDropOver(levels: DoubleArray, range: IntRange, hops: Int): Double =
        (range.first + hops..minOf(range.last, levels.size - 1))
            .maxOf { levels[it - hops] - levels[it] }

    private fun previous(levels: DoubleArray, hop: Int, hops: Int): Double =
        if (hop - hops < 0) Double.NaN else levels[hop - hops]

    /** Largest fall of the level below the mean of the preceding [referenceMs]. */
    private fun maxDrop(levels: DoubleArray, referenceMs: Double, range: IntRange = levels.indices): Double {
        val span = (referenceMs / AudioConstants.FRAME_DURATION_MS).toInt()
        var worst = 0.0
        for (index in (range.first + span)..minOf(range.last, levels.size - 1)) {
            val reference = levels.slice(index - span, index).filter { it > SILENT_DB }
            if (reference.size < span / 2) continue
            worst = maxOf(worst, reference.average() - levels[index])
        }
        return worst
    }

    /** Largest fall of the level over [shortMs] within [range], the speed a release gives the level. */
    private fun maxShortDrop(levels: DoubleArray, range: IntRange = levels.indices, shortMs: Double = SoundingToneGate.FAST_DROP_MS): Double {
        val span = (shortMs / AudioConstants.FRAME_DURATION_MS).toInt()
        var worst = 0.0
        for (index in (range.first + span)..minOf(range.last, levels.size - 1)) {
            worst = maxOf(worst, levels[index - span] - levels[index])
        }
        return worst
    }

    private fun DoubleArray.slice(from: Int, to: Int): List<Double> = (from until to).map { this[it] }

    private fun percentile(values: List<Double>, percent: Int): Double =
        values.sorted()[(values.size * percent / 100).coerceAtMost(values.size - 1)]

    /**
     * A D4 with a harmonic stack, played at the gain given by [levels]: a list of (seconds, dB) points
     * joined by straight ramps in dB, so a stop is a fast ramp rather than a click.
     */
    private fun enveloped(seconds: Double, levels: List<Pair<Double, Double>>): FloatArray {
        val samples = (seconds * SAMPLE_RATE).toInt()
        val freq = NoteUtils.midiToFrequency(D4_MIDI, 440f).toDouble()
        val out = FloatArray(samples)
        var peak = 0.0
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            var value = 0.0
            for (h in 1..8) value += sin(2 * PI * freq * h * t) / h
            out[i] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        val inv = (0.5 / peak).toFloat()
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            out[i] *= inv * dBToLinear(gainAt(levels, t)).toFloat()
        }
        return out
    }

    private fun gainAt(levels: List<Pair<Double, Double>>, seconds: Double): Double {
        if (seconds <= levels.first().first) return levels.first().second
        for (i in 1 until levels.size) {
            val (toSeconds, toDb) = levels[i]
            if (seconds <= toSeconds) {
                val (fromSeconds, fromDb) = levels[i - 1]
                val progress = (seconds - fromSeconds) / (toSeconds - fromSeconds)
                return fromDb + (toDb - fromDb) * progress
            }
        }
        return levels.last().second
    }

    private fun dBToLinear(db: Double): Double = 10.0.pow(db / 20.0)
}
